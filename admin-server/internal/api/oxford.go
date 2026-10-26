package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// Oxford is the setup for the picture dictionary page. Root holds the book and its built topics; oxford_cli.py
// lists them, applies hand corrections (fixes.json) and sends changed topics to the phone-facing server.
type Oxford struct {
	Python   string
	ToolsDir string
	Root     string
}

var topicCodeRe = regexp.MustCompile(`^V[0-9]{2}-[0-9]{2}$`)

func (o *Oxford) args(cmd string, more ...string) []string {
	return append([]string{filepath.Join(o.ToolsDir, "oxford_cli.py"), "--root", o.Root, cmd}, more...)
}

func (s *Server) oxfordRoutes(g *gin.RouterGroup) {
	g.GET("/oxford", s.oxfordList)
	g.POST("/oxford/fix", s.oxfordJob("fix", "应用词条修正", true))
	g.POST("/oxford/upload", s.oxfordJob("upload", "上传牛津主题", false))
}

// oxfordList runs the tool's "list" command: every built topic and how it compares with the server.
func (s *Server) oxfordList(c *gin.Context) {
	o := s.cfg.Oxford
	ctx, cancel := context.WithTimeout(c.Request.Context(), 120*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, o.Python, o.args("list")...)
	cmd.Dir = o.ToolsDir
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil || !json.Valid(out.Bytes()) {
		c.JSON(http.StatusBadGateway, gin.H{"error": "读取牛津主题列表失败"})
		return
	}
	c.Data(http.StatusOK, "application/json; charset=utf-8", out.Bytes())
}

// oxfordJob starts fix or upload for the topic codes in the body (every topic when there are none).
// Fixing can make speech for new words, so it counts as a heavy job.
func (s *Server) oxfordJob(cmd, title string, heavy bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			Codes []string `json:"codes"`
		}
		_ = c.ShouldBindJSON(&req)
		for _, code := range req.Codes {
			if !topicCodeRe.MatchString(code) {
				c.JSON(http.StatusBadRequest, gin.H{"error": "bad topic code"})
				return
			}
		}
		label := title + "：全部"
		if len(req.Codes) > 0 {
			label = title + "：" + strings.Join(req.Codes, "、")
		}
		o := s.cfg.Oxford
		s.runPython(c, o.Python, o.ToolsDir, "oxford-"+cmd, label, heavy, o.args(cmd, req.Codes...))
	}
}
