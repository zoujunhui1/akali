package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/akali"
	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
)

// Upstream is the part of the phone-facing server the article pages use.
type Upstream interface {
	Configured() bool
	Articles(ctx context.Context) ([]akali.Article, error)
	DeleteArticle(ctx context.Context, code string) error
	Stats(ctx context.Context) (json.RawMessage, error)
}

// Articles is the setup for the article import pages. The cleaning and uploading is done by the Python
// tool reading_import.py, which reads the server address and tokens from this program's environment.
type Articles struct {
	Upstream Upstream
	Python   string // the interpreter, e.g. the tools venv's python
	ToolsDir string // where reading_import.py lives
	WorkDir  string // uploaded files and drafts are kept here for a short while
}

const (
	maxUpload   = 40 << 20
	maxText     = 2 << 20
	draftMaxAge = 24 * time.Hour
)

var (
	articleCodeRe = regexp.MustCompile(`^R[0-9]{4,6}$`)
	fileSuffixes  = map[string]bool{".pdf": true, ".epub": true, ".txt": true, ".md": true}
)

func (s *Server) articleRoutes(g *gin.RouterGroup) {
	g.GET("/articles", s.listArticles)
	g.POST("/articles/preview", s.previewArticle)
	g.GET("/articles/drafts/:id", s.getDraft)
	g.POST("/articles/upload", s.uploadArticle)
	g.DELETE("/articles/:code", s.deleteArticle)
}

func (s *Server) listArticles(c *gin.Context) {
	a := s.cfg.Articles
	if !a.Upstream.Configured() {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "还没有配置第一台的地址和口令"})
		return
	}
	list, err := a.Upstream.Articles(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "读不到第一台的文章列表"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"articles": list})
}

func (s *Server) deleteArticle(c *gin.Context) {
	code := c.Param("code")
	if !articleCodeRe.MatchString(code) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "bad article code"})
		return
	}
	if !s.cfg.Articles.Upstream.Configured() {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "还没有配置第一台的地址和口令"})
		return
	}
	if err := s.cfg.Articles.Upstream.DeleteArticle(c.Request.Context(), code); err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "第一台没有删成功"})
		return
	}
	c.JSON(http.StatusOK, gin.H{})
}

func newToken() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (a *Articles) draftPath(id int64) string {
	return filepath.Join(a.WorkDir, "draft-"+strconv.FormatInt(id, 10)+".json")
}

// sweep removes inputs and drafts older than a day.
func (a *Articles) sweep() {
	entries, _ := os.ReadDir(a.WorkDir)
	for _, e := range entries {
		if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > draftMaxAge {
			_ = os.RemoveAll(filepath.Join(a.WorkDir, e.Name()))
		}
	}
}

type previewRequest struct {
	Kind   string `json:"kind"` // url | text
	Target string `json:"target"`
	Title  string `json:"title"`
}

// previewArticle starts a job that cleans the article and writes a draft. It accepts JSON ({kind: url|text,
// target, title}) or a multipart form with a "file" (PDF, EPUB, txt, md) and an optional "title".
func (s *Server) previewArticle(c *gin.Context) {
	a := s.cfg.Articles
	if err := os.MkdirAll(a.WorkDir, 0o700); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	a.sweep()
	var kind, target, source, title, label string
	inDir := filepath.Join(a.WorkDir, "in-"+newToken())

	if strings.HasPrefix(c.ContentType(), "multipart/") {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxUpload+(1<<20))
		fh, err := c.FormFile("file")
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "没有收到文件，或文件超过 40MB"})
			return
		}
		name := filepath.Base(fh.Filename)
		if !fileSuffixes[strings.ToLower(filepath.Ext(name))] {
			c.JSON(http.StatusBadRequest, gin.H{"error": "只支持 PDF、EPUB、txt、md 文件"})
			return
		}
		if err := os.MkdirAll(inDir, 0o700); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
			return
		}
		target = filepath.Join(inDir, "upload"+strings.ToLower(filepath.Ext(name)))
		if err := c.SaveUploadedFile(fh, target); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
			return
		}
		kind, source, title, label = "file", name, strings.TrimSpace(c.PostForm("title")), name
	} else {
		var req previewRequest
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxText+(1<<20))
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "bad request"})
			return
		}
		title = strings.TrimSpace(req.Title)
		switch req.Kind {
		case "url":
			u, err := url.Parse(strings.TrimSpace(req.Target))
			if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
				c.JSON(http.StatusBadRequest, gin.H{"error": "请填 http 或 https 开头的网址"})
				return
			}
			kind, target, label = "url", u.String(), u.Host
		case "text":
			if strings.TrimSpace(req.Target) == "" || len(req.Target) > maxText {
				c.JSON(http.StatusBadRequest, gin.H{"error": "文字为空，或超过 2MB"})
				return
			}
			if err := os.MkdirAll(inDir, 0o700); err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
				return
			}
			target = filepath.Join(inDir, "Pasted text.txt")
			if err := os.WriteFile(target, []byte(req.Target), 0o600); err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
				return
			}
			kind, source, label = "file", "", "粘贴的文字"
		default:
			c.JSON(http.StatusBadRequest, gin.H{"error": "kind must be url or text"})
			return
		}
	}

	params, _ := json.Marshal(map[string]string{"kind": kind, "source": label})
	id, err := s.jobs.Runner.Submit(c.Request.Context(), jobs.Spec{Kind: "article-preview", Title: "整理文章：" + label, Params: params},
		func(ctx context.Context, run *jobs.Run) error {
			defer os.RemoveAll(inDir)
			args := []string{filepath.Join(a.ToolsDir, "reading_import.py"), kind, target, "--draft-out=" + a.draftPath(run.ID)}
			if kind == "file" {
				args = append(args, "--source="+source)
			}
			if title != "" {
				args = append(args, "--title="+title)
			}
			return jobs.RunCommand(ctx, run, a.ToolsDir, a.Python, args...)
		})
	if err != nil {
		_ = os.RemoveAll(inDir)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id})
}

type draftFile struct {
	Source   string `json:"source"`
	Articles []struct {
		Title string     `json:"title"`
		Body  [][]string `json:"body"`
	} `json:"articles"`
}

func (a *Articles) readDraft(id int64) (draftFile, error) {
	var d draftFile
	data, err := os.ReadFile(a.draftPath(id))
	if err != nil {
		return d, err
	}
	return d, json.Unmarshal(data, &d)
}

// getDraft summarises a draft for the preview page: title, size and the first sentence of each article.
func (s *Server) getDraft(c *gin.Context) {
	id, ok := jobID(c)
	if !ok {
		return
	}
	d, err := s.cfg.Articles.readDraft(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "没有这份草稿（可能已过期）"})
		return
	}
	type item struct {
		Title      string `json:"title"`
		Paragraphs int    `json:"paragraphs"`
		Sentences  int    `json:"sentences"`
		Words      int    `json:"words"`
		First      string `json:"first"`
	}
	items := []item{}
	for _, a := range d.Articles {
		it := item{Title: a.Title, Paragraphs: len(a.Body)}
		for _, p := range a.Body {
			it.Sentences += len(p)
			for _, sent := range p {
				it.Words += len(strings.Fields(sent))
			}
		}
		if len(a.Body) > 0 && len(a.Body[0]) > 0 {
			it.First = string([]rune(a.Body[0][0])[:min(120, len([]rune(a.Body[0][0])))])
		}
		items = append(items, it)
	}
	c.JSON(http.StatusOK, gin.H{"source": d.Source, "articles": items})
}

// uploadArticle starts a job that sends a draft to the phone-facing server. A title is only taken when the
// draft holds a single article.
func (s *Server) uploadArticle(c *gin.Context) {
	a := s.cfg.Articles
	var req struct {
		Draft int64  `json:"draft"`
		Title string `json:"title"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Draft <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "bad request"})
		return
	}
	if !a.Upstream.Configured() {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "还没有配置第一台的地址和口令"})
		return
	}
	d, err := a.readDraft(req.Draft)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "没有这份草稿（可能已过期）"})
		return
	}
	title := strings.TrimSpace(req.Title)
	label := fmt.Sprintf("%d 篇", len(d.Articles))
	if len(d.Articles) == 1 {
		label = d.Articles[0].Title
		if title != "" {
			label = title
		}
	}
	path := a.draftPath(req.Draft)
	id, err := s.jobs.Runner.Submit(c.Request.Context(), jobs.Spec{Kind: "article-upload", Title: "上传文章：" + label},
		func(ctx context.Context, run *jobs.Run) error {
			args := []string{filepath.Join(a.ToolsDir, "reading_import.py"), "draft", path}
			if title != "" {
				args = append(args, "--title="+title)
			}
			if err := jobs.RunCommand(ctx, run, a.ToolsDir, a.Python, args...); err != nil {
				return err
			}
			_ = os.Remove(path)
			return nil
		})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id})
}
