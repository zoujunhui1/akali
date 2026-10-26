package api

import (
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"syscall"

	"github.com/gin-gonic/gin"
)

// used says how many AI calls have been let through today.
func (l *limiter) used() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.day != todayUTC() {
		return 0
	}
	return l.count
}

// dirSize adds up the size of every file below dir.
func dirSize(dir string) int64 {
	var n int64
	filepath.WalkDir(dir, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, err := d.Info(); err == nil {
				n += info.Size()
			}
		}
		return nil
	})
	return n
}

// GET /admin/stats: read-only numbers for the admin system's status page.
func (s *Server) stats(c *gin.Context) {
	st, err := s.store.Stats(c.Request.Context())
	if err != nil {
		storeFail(c, err)
		return
	}
	out := gin.H{
		"rev": st.Rev, "lessons": st.Lessons, "vocab_topics": st.VocabTopics, "articles": st.Articles,
		"notebook": st.Notebook, "favorites": st.Favorites, "mastered": st.Mastered,
		"ai":          gin.H{"configured": s.cfg.AI != nil, "used_today": s.limit.used(), "limit": s.cfg.AIPerDay},
		"audio_bytes": dirSize(s.cfg.AudioDir),
	}
	if info, err := os.Stat(s.dictPath()); err == nil {
		out["dict"] = gin.H{"size": info.Size()}
	}
	var fsStat syscall.Statfs_t
	if syscall.Statfs(s.cfg.AudioDir, &fsStat) == nil {
		out["disk_free_bytes"] = int64(fsStat.Bavail) * int64(fsStat.Bsize)
	}
	c.JSON(http.StatusOK, out)
}
