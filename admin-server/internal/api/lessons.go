package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
)

// Lessons is the setup for the listening lesson pages. The work is done by the Python tool lessons_cli.py,
// which reads the phone-facing server's address and tokens (and the Feishu settings) from this program's environment.
type Lessons struct {
	Python   string // the interpreter, e.g. the tools venv's python
	ToolsDir string // where lessons_cli.py lives
	Library  string // the folders holding each lesson's audio, transcript and timestamps
}

var lessonCodeRe = regexp.MustCompile(`^[ABC][12]-[0-9]{3}$`)

func (s *Server) lessonRoutes(g *gin.RouterGroup) {
	g.GET("/lessons", s.listLessons)
	g.POST("/lessons/process", s.processLesson)
	g.POST("/lessons/vocab", s.vocabLessons)
	g.POST("/lessons/:code/redo", s.lessonAction("redo", "重做时间戳", true))
	g.POST("/lessons/:code/upload", s.lessonAction("upload", "重新上传", false))
	g.DELETE("/lessons/:code", s.lessonAction("delete", "从服务器删除", false))
}

func (l *Lessons) args(cmd string, more ...string) []string {
	return append([]string{filepath.Join(l.ToolsDir, "lessons_cli.py"), "--library", l.Library, cmd}, more...)
}

// listLessons runs the tool's "list" command, which prints the JSON the page shows.
func (s *Server) listLessons(c *gin.Context) {
	l := s.cfg.Lessons
	ctx, cancel := context.WithTimeout(c.Request.Context(), 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, l.Python, l.args("list")...)
	cmd.Dir = l.ToolsDir
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil || !json.Valid(out.Bytes()) {
		c.JSON(http.StatusBadGateway, gin.H{"error": "读取听力课列表失败"})
		return
	}
	c.Data(http.StatusOK, "application/json; charset=utf-8", out.Bytes())
}

func (s *Server) pythonJob(c *gin.Context, kind, title string, heavy bool, args []string) {
	l := s.cfg.Lessons
	s.runPython(c, l.Python, l.ToolsDir, kind, title, heavy, args)
}

// runPython queues a job that runs one of the Python tools and answers with the job's id.
func (s *Server) runPython(c *gin.Context, python, dir, kind, title string, heavy bool, args []string) {
	id, err := s.jobs.Runner.Submit(c.Request.Context(), jobs.Spec{Kind: kind, Title: title, Heavy: heavy},
		func(ctx context.Context, run *jobs.Run) error {
			return jobs.RunCommand(ctx, run, dir, python, args...)
		})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id})
}

// processLesson: fetch an ELLLO lesson, recognise its speech (heavy), make vocab, upload.
func (s *Server) processLesson(c *gin.Context) {
	var req struct {
		URL string `json:"url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "bad request"})
		return
	}
	u, err := url.Parse(strings.TrimSpace(req.URL))
	host := ""
	if err == nil {
		host = strings.ToLower(u.Hostname())
	}
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || (host != "elllo.org" && host != "www.elllo.org") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请填 elllo.org 的课程页面网址"})
		return
	}
	name := u.Path[strings.LastIndex(u.Path, "/")+1:]
	s.pythonJob(c, "lesson-process", "处理新课："+name, true, s.cfg.Lessons.args("process", u.String()))
}

// lessonAction runs one of the per-lesson commands (redo, upload, delete) for the lesson code in the path.
func (s *Server) lessonAction(cmd, label string, heavy bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		code := c.Param("code")
		if !lessonCodeRe.MatchString(code) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "bad lesson code"})
			return
		}
		s.pythonJob(c, "lesson-"+cmd, label+"："+code, heavy, s.cfg.Lessons.args(cmd, code))
	}
}

// vocabLessons remakes vocab from the Feishu table for the given lessons, or for every lesson that has words.
func (s *Server) vocabLessons(c *gin.Context) {
	var req struct {
		Codes []string `json:"codes"`
	}
	_ = c.ShouldBindJSON(&req)
	for _, code := range req.Codes {
		if !lessonCodeRe.MatchString(code) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "bad lesson code"})
			return
		}
	}
	title := "更新词表：全部"
	if len(req.Codes) > 0 {
		title = "更新词表：" + strings.Join(req.Codes, "、")
	}
	s.pythonJob(c, "lesson-vocab", title, false, s.cfg.Lessons.args("vocab", req.Codes...))
}
