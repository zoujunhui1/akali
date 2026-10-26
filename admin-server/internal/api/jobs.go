package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

// JobReader is the read side of the job tables.
type JobReader interface {
	ListJobs(ctx context.Context, limit int) ([]store.Job, error)
	GetJob(ctx context.Context, id int64) (store.Job, error)
	JobLogs(ctx context.Context, jobID, after int64, limit int) ([]store.LogLine, error)
}

// Jobs is what the job endpoints work with.
type Jobs struct {
	Runner *jobs.Runner
	Store  JobReader
	// SelfCheckStep is how long each step of the self-check job takes (default one second).
	SelfCheckStep time.Duration
}

const (
	listLimit = 50
	logLimit  = 500
	// the self-check job has this many steps
	selfCheckSteps = 6
)

func (s *Server) jobRoutes(g *gin.RouterGroup) {
	g.GET("/jobs", s.listJobs)
	g.GET("/jobs/:id", s.getJob)
	g.POST("/jobs/:id/cancel", s.cancelJob)
	g.POST("/jobs/selfcheck", s.selfCheck)
}

func (s *Server) listJobs(c *gin.Context) {
	list, err := s.jobs.Store.ListJobs(c.Request.Context(), listLimit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"jobs": list})
}

func jobID(c *gin.Context) (int64, bool) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "bad job id"})
		return 0, false
	}
	return id, true
}

// getJob returns the job and its log lines after ?after=<line id>, so the page can ask only for new lines.
func (s *Server) getJob(c *gin.Context) {
	id, ok := jobID(c)
	if !ok {
		return
	}
	after, _ := strconv.ParseInt(c.Query("after"), 10, 64)
	ctx := c.Request.Context()
	job, err := s.jobs.Store.GetJob(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, gin.H{"error": "job not found"})
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	lines, err := s.jobs.Store.JobLogs(ctx, id, after, logLimit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"job": job, "logs": lines})
}

func (s *Server) cancelJob(c *gin.Context) {
	id, ok := jobID(c)
	if !ok {
		return
	}
	switch err := s.jobs.Runner.Cancel(id); {
	case errors.Is(err, jobs.ErrNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": "job not found"})
	case errors.Is(err, jobs.ErrFinished):
		c.JSON(http.StatusConflict, gin.H{"error": "the job has already finished"})
	case err != nil:
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
	default:
		c.JSON(http.StatusOK, gin.H{})
	}
}

// selfCheck starts a harmless job that only logs a few lines, to see the queue working.
func (s *Server) selfCheck(c *gin.Context) {
	var body struct {
		Heavy bool `json:"heavy"`
	}
	_ = c.ShouldBindJSON(&body)
	step := s.jobs.SelfCheckStep
	if step == 0 {
		step = time.Second
	}
	title := "自检任务（轻）"
	if body.Heavy {
		title = "自检任务（重）"
	}
	id, err := s.jobs.Runner.Submit(c.Request.Context(), jobs.Spec{Kind: "selfcheck", Title: title, Heavy: body.Heavy},
		func(ctx context.Context, run *jobs.Run) error {
			for i := 1; i <= selfCheckSteps; i++ {
				select {
				case <-time.After(step):
					run.Log("step " + strconv.Itoa(i) + " of " + strconv.Itoa(selfCheckSteps))
				case <-ctx.Done():
					return ctx.Err()
				}
			}
			run.Log("done")
			return nil
		})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id})
}
