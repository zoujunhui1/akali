// Package api is the HTTP interface: read endpoints for the app (app token)
// and write endpoints for the Mac tool (admin token).
package api

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/llm"
	"github.com/zoujunhui1/akali/server/internal/store"
)

const maxAudioBytes = 100 << 20

type Config struct {
	AppToken   string
	AdminToken string
	AudioDir   string
	AI         *llm.Client // nil = no AI key configured
	AIPerDay   int         // 0 = 1000
}

type Server struct {
	cfg   Config
	store *store.Store
	limit *limiter
}

func New(cfg Config, s *store.Store) (*gin.Engine, error) {
	if cfg.AppToken == "" || cfg.AdminToken == "" {
		return nil, errors.New("app token and admin token must both be set")
	}
	if cfg.AppToken == cfg.AdminToken {
		return nil, errors.New("app token and admin token must differ")
	}
	if err := os.MkdirAll(cfg.AudioDir, 0o755); err != nil {
		return nil, err
	}
	if cfg.AIPerDay == 0 {
		cfg.AIPerDay = 1000
	}
	srv := &Server{cfg: cfg, store: s, limit: &limiter{max: cfg.AIPerDay}}

	r := gin.New()
	r.Use(gin.Recovery(), gin.Logger())
	r.GET("/healthz", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	app := r.Group("/api/v1", bearer(cfg.AppToken))
	app.GET("/lessons", srv.list)
	app.GET("/lessons/:code", srv.get)
	app.GET("/lessons/:code/audio", srv.audio)

	admin := r.Group("/admin", bearer(cfg.AdminToken))
	admin.PUT("/lessons/:code", srv.upsert)
	admin.PUT("/lessons/:code/audio", srv.putAudio)
	admin.PUT("/lessons/:code/timestamps", srv.putTimestamps)
	admin.PUT("/lessons/:code/vocab", srv.putVocab)
	admin.DELETE("/lessons/:code", srv.remove)
	admin.GET("/stats", srv.stats)
	srv.vocabRoutes(app, admin)
	srv.aiRoutes(app)
	srv.marksRoutes(app)
	srv.readingRoutes(app, admin)
	return r, nil
}

func bearer(token string) gin.HandlerFunc {
	want := []byte(token)
	return func(c *gin.Context) {
		got := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
		if subtle.ConstantTimeCompare([]byte(got), want) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "bad or missing token"})
		}
	}
}

func fail(c *gin.Context, status int, err error) {
	c.AbortWithStatusJSON(status, gin.H{"error": err.Error()})
}

func storeFail(c *gin.Context, err error) {
	if errors.Is(err, store.ErrNotFound) {
		fail(c, http.StatusNotFound, err)
		return
	}
	fail(c, http.StatusInternalServerError, err)
}

// code parses :code, answering 400 itself on failure.
func code(c *gin.Context) (level string, number int, ok bool) {
	level, number, err := store.ParseCode(c.Param("code"))
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return "", 0, false
	}
	return level, number, true
}

func (s *Server) audioPath(level string, number int) string {
	return filepath.Join(s.cfg.AudioDir, store.Code(level, number)+".mp3")
}

type audioInfo struct {
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type lessonJSON struct {
	Code       string          `json:"code"`
	Level      string          `json:"level"`
	Number     int             `json:"number"`
	Title      string          `json:"title"`
	SourceURL  *string         `json:"source_url"`
	Audio      *audioInfo      `json:"audio"`
	Rev        int64           `json:"rev"`
	Deleted    bool            `json:"deleted"`
	CreatedAt  int64           `json:"created_at"` // unix milliseconds
	Transcript *string         `json:"transcript,omitempty"`
	Timestamps json.RawMessage `json:"timestamps,omitempty"`
	Vocab      json.RawMessage `json:"vocab,omitempty"`
}

func toJSON(l store.Lesson, content bool) lessonJSON {
	out := lessonJSON{
		Code: l.Code(), Level: l.Level, Number: l.Number, Title: l.Title,
		SourceURL: l.SourceURL, Rev: l.Rev, Deleted: l.Deleted, CreatedAt: l.CreatedAt.UnixMilli(),
	}
	if l.AudioSize > 0 {
		out.Audio = &audioInfo{Size: l.AudioSize, SHA256: l.AudioSHA}
	}
	if content && !l.Deleted {
		out.Transcript = &l.Transcript
		out.Timestamps = l.Timestamps
		out.Vocab = l.Vocab
	}
	return out
}

// GET /api/v1/lessons?since=N&content=0|1
// Lessons changed after revision N (deleted ones as tombstones). content=1 includes text, timestamps and vocab.
func (s *Server) list(c *gin.Context) {
	var since int64
	if v := c.Query("since"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &since); err != nil || since < 0 {
			fail(c, http.StatusBadRequest, errors.New("since must be a non-negative integer"))
			return
		}
	}
	content := c.Query("content") == "1"
	ls, rev, err := s.store.Changes(c.Request.Context(), since)
	if err != nil {
		storeFail(c, err)
		return
	}
	out := make([]lessonJSON, 0, len(ls))
	for _, l := range ls {
		out = append(out, toJSON(l, content))
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev, "lessons": out})
}

// GET /api/v1/lessons/:code
func (s *Server) get(c *gin.Context) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	l, err := s.store.Get(c.Request.Context(), level, number)
	if err != nil {
		storeFail(c, err)
		return
	}
	c.JSON(http.StatusOK, toJSON(l, true))
}

// GET /api/v1/lessons/:code/audio (supports Range)
func (s *Server) audio(c *gin.Context) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	l, err := s.store.Get(c.Request.Context(), level, number)
	if err != nil {
		storeFail(c, err)
		return
	}
	if l.AudioSize == 0 {
		fail(c, http.StatusNotFound, errors.New("this lesson has no audio yet"))
		return
	}
	f, err := os.Open(s.audioPath(level, number))
	if err != nil {
		fail(c, http.StatusNotFound, errors.New("audio file is missing on the server"))
		return
	}
	defer f.Close()
	c.Header("Content-Type", "audio/mpeg")
	c.Header("ETag", `"`+l.AudioSHA+`"`)
	http.ServeContent(c.Writer, c.Request, l.Code()+".mp3", l.UpdatedAt, f)
}

type upsertBody struct {
	Title      string  `json:"title"`
	SourceURL  *string `json:"source_url"`
	Transcript string  `json:"transcript"`
}

// PUT /admin/lessons/:code  {title, source_url, transcript}
func (s *Server) upsert(c *gin.Context) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	var b upsertBody
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if strings.TrimSpace(b.Title) == "" {
		fail(c, http.StatusBadRequest, errors.New("title is required"))
		return
	}
	if err := s.store.Upsert(c.Request.Context(), level, number, b.Title, b.SourceURL, b.Transcript); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

// PUT /admin/lessons/:code/audio  (raw mp3 body)
func (s *Server) putAudio(c *gin.Context) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	if _, err := s.store.Get(ctx, level, number); err != nil {
		storeFail(c, err)
		return
	}
	tmp, err := os.CreateTemp(s.cfg.AudioDir, ".upload-*")
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	body := http.MaxBytesReader(c.Writer, c.Request.Body, maxAudioBytes)
	n, err := io.Copy(io.MultiWriter(tmp, h), body)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if n == 0 {
		fail(c, http.StatusBadRequest, errors.New("empty audio body"))
		return
	}
	if err := os.Rename(tmp.Name(), s.audioPath(level, number)); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	if err := s.store.SetAudio(ctx, level, number, n, hex.EncodeToString(h.Sum(nil))); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

// PUT /admin/lessons/:code/timestamps  (the timestamps.json document)
func (s *Server) putTimestamps(c *gin.Context) {
	s.putDoc(c, func(doc json.RawMessage) error {
		var d struct {
			Sentences []struct {
				Text  string   `json:"text"`
				Start *float64 `json:"start"`
				End   *float64 `json:"end"`
			} `json:"sentences"`
		}
		if err := json.Unmarshal(doc, &d); err != nil {
			return err
		}
		if len(d.Sentences) == 0 {
			return errors.New("sentences must not be empty")
		}
		for i, s := range d.Sentences {
			if s.Text == "" || s.Start == nil || s.End == nil || *s.End < *s.Start {
				return fmt.Errorf("sentence %d is malformed", i)
			}
		}
		return nil
	}, s.store.SetTimestamps)
}

// PUT /admin/lessons/:code/vocab  (the vocab.json document; items may be empty)
func (s *Server) putVocab(c *gin.Context) {
	s.putDoc(c, func(doc json.RawMessage) error {
		var d struct {
			Items *[]json.RawMessage `json:"items"`
		}
		if err := json.Unmarshal(doc, &d); err != nil {
			return err
		}
		if d.Items == nil {
			return errors.New("items is required")
		}
		return nil
	}, s.store.SetVocab)
}

func (s *Server) putDoc(c *gin.Context, validate func(json.RawMessage) error,
	save func(ctx context.Context, level string, number int, doc json.RawMessage) error) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 8<<20))
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if err := validate(body); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if err := save(c.Request.Context(), level, number, body); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

// DELETE /admin/lessons/:code
func (s *Server) remove(c *gin.Context) {
	level, number, ok := code(c)
	if !ok {
		return
	}
	if err := s.store.Delete(c.Request.Context(), level, number); err != nil {
		storeFail(c, err)
		return
	}
	_ = os.Remove(s.audioPath(level, number))
	c.Status(http.StatusNoContent)
}
