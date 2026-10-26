package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/gin-gonic/gin"
)

// Dict is the setup for the offline dictionary page. dict_build.py makes the dictionary file from the ECDICT
// source under Root (ecdict/ecdict.csv in, build/dict.db out) and sends it to the phone-facing server.
type Dict struct {
	Python   string
	ToolsDir string
	Root     string
}

func (d *Dict) args(cmd string) []string {
	args := []string{filepath.Join(d.ToolsDir, "dict_build.py"), cmd, "--out", filepath.Join(d.Root, "build", "dict.db")}
	if cmd == "build" {
		args = append(args, "--source", filepath.Join(d.Root, "ecdict", "ecdict.csv"))
	}
	return args
}

func (s *Server) dictRoutes(g *gin.RouterGroup) {
	g.GET("/dict", s.dictStatus)
	g.POST("/dict/build", s.dictJob("build", "重新制作字典"))
	g.POST("/dict/compare", s.dictJob("compare", "对比字典和服务器"))
	g.POST("/dict/upload", s.dictJob("upload", "上传字典"))
}

// dictStatus runs the tool's "status" command: the local file's size and checksum next to the server's.
func (s *Server) dictStatus(c *gin.Context) {
	d := s.cfg.Dict
	ctx, cancel := context.WithTimeout(c.Request.Context(), 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, d.Python, d.args("status")...)
	cmd.Dir = d.ToolsDir
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil || !json.Valid(out.Bytes()) {
		c.JSON(http.StatusBadGateway, gin.H{"error": "读取字典状态失败"})
		return
	}
	c.Data(http.StatusOK, "application/json; charset=utf-8", out.Bytes())
}

func (s *Server) dictJob(cmd, title string) gin.HandlerFunc {
	return func(c *gin.Context) {
		d := s.cfg.Dict
		s.runPython(c, d.Python, d.ToolsDir, "dict-"+cmd, title, false, d.args(cmd))
	}
}
