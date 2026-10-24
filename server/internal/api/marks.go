package api

import (
	"errors"
	"fmt"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/store"
)

const maxMarksPerRequest = 500

func (s *Server) marksRoutes(app *gin.RouterGroup) {
	app.GET("/marks", s.marksList)
	app.PUT("/marks", s.marksPut)
}

type markJSON struct {
	Code  string `json:"code"`
	Key   string `json:"key"`
	State string `json:"state"` // "mastered", "favorite" or "none"
}

// GET /api/v1/marks?since=N
func (s *Server) marksList(c *gin.Context) {
	var since int64
	if v := c.Query("since"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &since); err != nil || since < 0 {
			fail(c, http.StatusBadRequest, errors.New("since must be a non-negative integer"))
			return
		}
	}
	ms, rev, err := s.store.MarkChanges(c.Request.Context(), since)
	if err != nil {
		storeFail(c, err)
		return
	}
	out := make([]markJSON, 0, len(ms))
	for _, m := range ms {
		out = append(out, markJSON{Code: m.Code, Key: m.Key, State: m.State})
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev, "marks": out})
}

// PUT /api/v1/marks  {marks:[{code, key, state}]}  ->  {rev}
func (s *Server) marksPut(c *gin.Context) {
	var b struct {
		Marks []markJSON `json:"marks"`
	}
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if len(b.Marks) == 0 || len(b.Marks) > maxMarksPerRequest {
		fail(c, http.StatusBadRequest, fmt.Errorf("send between 1 and %d marks", maxMarksPerRequest))
		return
	}
	marks := make([]store.Mark, 0, len(b.Marks))
	for i, m := range b.Marks {
		if _, _, err := store.ParseVocabCode(m.Code); err != nil || !store.ValidMarkKey(m.Key) || !store.ValidMarkState(m.State) {
			fail(c, http.StatusBadRequest, fmt.Errorf("mark %d is malformed", i))
			return
		}
		marks = append(marks, store.Mark{Code: m.Code, Key: m.Key, State: m.State})
	}
	rev, err := s.store.SetMarks(c.Request.Context(), marks)
	if err != nil {
		storeFail(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev})
}
