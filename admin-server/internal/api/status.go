package api

import (
	"net/http"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
)

// status answers the status page: is the phone-facing server up, what it holds (its own /admin/stats),
// how long it took to answer, and how much room is left on this machine. A server that cannot be reached
// is an ordinary answer ("online": false), not an error, so the page can say so.
func (s *Server) status(c *gin.Context) {
	a := s.cfg.Articles
	out := gin.H{"online": false}
	if a.Upstream.Configured() {
		start := time.Now()
		if stats, err := a.Upstream.Stats(c.Request.Context()); err != nil {
			out["error"] = "连不上第一台，或口令不对"
		} else {
			out["online"], out["server"], out["latency_ms"] = true, stats, time.Since(start).Milliseconds()
		}
	} else {
		out["error"] = "还没有配置第一台的地址和口令"
	}
	var fsStat syscall.Statfs_t
	if syscall.Statfs(a.WorkDir, &fsStat) == nil || syscall.Statfs("/", &fsStat) == nil {
		out["local_disk_free_bytes"] = int64(fsStat.Bavail) * int64(fsStat.Bsize)
	}
	c.JSON(http.StatusOK, out)
}
