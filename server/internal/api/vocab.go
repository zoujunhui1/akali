package api

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/store"
)

const maxBundleBytes = 64 << 20

func (s *Server) vocabRoutes(app, admin *gin.RouterGroup) {
	app.GET("/vocab", s.vocabList)
	app.GET("/vocab/:code/bundle", s.vocabBundle)
	admin.PUT("/vocab/:code", s.vocabUpsert)
	admin.PUT("/vocab/:code/bundle", s.vocabPutBundle)
	admin.DELETE("/vocab/:code", s.vocabRemove)
}

func vocabCode(c *gin.Context) (unit, number int, ok bool) {
	unit, number, err := store.ParseVocabCode(c.Param("code"))
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return 0, 0, false
	}
	return unit, number, true
}

func (s *Server) bundlePath(unit, number int) string {
	return filepath.Join(s.cfg.AudioDir, "vocab", store.VocabCode(unit, number)+".zip")
}

type vocabJSON struct {
	Code      string          `json:"code"`
	Unit      int             `json:"unit"`
	Number    int             `json:"number"`
	Title     string          `json:"title"`
	Pages     json.RawMessage `json:"pages"`
	Entries   json.RawMessage `json:"entries"`
	Notes     json.RawMessage `json:"notes,omitempty"`
	Bundle    *audioInfo      `json:"bundle"`
	Rev       int64           `json:"rev"`
	Deleted   bool            `json:"deleted"`
	CreatedAt int64           `json:"created_at"` // unix milliseconds
}

func toVocabJSON(l store.VocabLesson) vocabJSON {
	out := vocabJSON{
		Code: l.Code(), Unit: l.Unit, Number: l.Number, Title: l.Title, Pages: l.Pages, Entries: l.Entries, Notes: l.Notes,
		Rev: l.Rev, Deleted: l.Deleted, CreatedAt: l.CreatedAt.UnixMilli(),
	}
	if l.BundleSize > 0 {
		out.Bundle = &audioInfo{Size: l.BundleSize, SHA256: l.BundleSHA}
	}
	return out
}

// GET /api/v1/vocab?since=N
func (s *Server) vocabList(c *gin.Context) {
	var since int64
	if v := c.Query("since"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &since); err != nil || since < 0 {
			fail(c, http.StatusBadRequest, errors.New("since must be a non-negative integer"))
			return
		}
	}
	ls, rev, err := s.store.VocabChanges(c.Request.Context(), since)
	if err != nil {
		storeFail(c, err)
		return
	}
	out := make([]vocabJSON, 0, len(ls))
	for _, l := range ls {
		out = append(out, toVocabJSON(l))
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev, "lessons": out})
}

// GET /api/v1/vocab/:code/bundle (supports Range)
func (s *Server) vocabBundle(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	l, err := s.store.GetVocab(c.Request.Context(), unit, number)
	if err != nil {
		storeFail(c, err)
		return
	}
	if l.BundleSize == 0 {
		fail(c, http.StatusNotFound, errors.New("this topic has no files yet"))
		return
	}
	f, err := os.Open(s.bundlePath(unit, number))
	if err != nil {
		fail(c, http.StatusNotFound, errors.New("bundle file is missing on the server"))
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	c.Header("Content-Type", "application/zip")
	c.Header("ETag", `"`+l.BundleSHA+`"`)
	http.ServeContent(c.Writer, c.Request, l.Code()+".zip", st.ModTime(), f)
}

type vocabBody struct {
	Title   string          `json:"title"`
	Pages   json.RawMessage `json:"pages"`
	Entries json.RawMessage `json:"entries"`
}

// PUT /admin/vocab/:code  {title, pages, entries}
func (s *Server) vocabUpsert(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	var b vocabBody
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if strings.TrimSpace(b.Title) == "" {
		fail(c, http.StatusBadRequest, errors.New("title is required"))
		return
	}
	var pages []int
	if err := json.Unmarshal(b.Pages, &pages); err != nil || len(pages) == 0 {
		fail(c, http.StatusBadRequest, errors.New("pages must be a non-empty list of page numbers"))
		return
	}
	var entries []struct {
		Label string `json:"label"`
		Text  string `json:"text"`
		Page  int    `json:"page"`
		Audio string `json:"audio"`
	}
	if err := json.Unmarshal(b.Entries, &entries); err != nil {
		fail(c, http.StatusBadRequest, errors.New("entries must be a list"))
		return
	}
	for i, e := range entries {
		if e.Label == "" || e.Text == "" || !safeName(e.Audio) {
			fail(c, http.StatusBadRequest, fmt.Errorf("entry %d is malformed", i))
			return
		}
	}
	if err := s.store.UpsertVocab(c.Request.Context(), unit, number, b.Title, b.Pages, b.Entries); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

// safeName accepts a relative path that stays inside the bundle.
func safeName(name string) bool {
	if name == "" || strings.Contains(name, "\\") || path.IsAbs(name) {
		return false
	}
	for _, part := range strings.Split(name, "/") {
		if part == "" || part == ".." || part == "." {
			return false
		}
	}
	return true
}

// PUT /admin/vocab/:code/bundle  (raw zip body: page images and audio clips)
func (s *Server) vocabPutBundle(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	if _, err := s.store.GetVocab(ctx, unit, number); err != nil {
		storeFail(c, err)
		return
	}
	dir := filepath.Join(s.cfg.AudioDir, "vocab")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	tmp, err := os.CreateTemp(dir, ".upload-*")
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, h), http.MaxBytesReader(c.Writer, c.Request.Body, maxBundleBytes))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if err := checkZip(tmp.Name()); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if err := os.Rename(tmp.Name(), s.bundlePath(unit, number)); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	if err := s.store.SetVocabBundle(ctx, unit, number, n, hex.EncodeToString(h.Sum(nil))); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func checkZip(file string) error {
	r, err := zip.OpenReader(file)
	if err != nil {
		return errors.New("body is not a zip file")
	}
	defer r.Close()
	if len(r.File) == 0 {
		return errors.New("zip is empty")
	}
	for _, f := range r.File {
		if !safeName(strings.TrimSuffix(f.Name, "/")) {
			return fmt.Errorf("unsafe path in zip: %q", f.Name)
		}
	}
	return nil
}

// DELETE /admin/vocab/:code
func (s *Server) vocabRemove(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	if err := s.store.DeleteVocab(c.Request.Context(), unit, number); err != nil {
		storeFail(c, err)
		return
	}
	_ = os.Remove(s.bundlePath(unit, number))
	c.Status(http.StatusNoContent)
}
