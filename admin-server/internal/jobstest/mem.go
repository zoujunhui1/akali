// Package jobstest has an in-memory job store so the runner and the API can be tested without a database.
package jobstest

import (
	"context"
	"sync"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

type Mem struct {
	mu   sync.Mutex
	next int64
	jobs map[int64]*store.Job
	logs map[int64][]store.LogLine
}

func New() *Mem {
	return &Mem{jobs: map[int64]*store.Job{}, logs: map[int64][]store.LogLine{}}
}

func (m *Mem) CreateJob(_ context.Context, j store.Job) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.next++
	j.ID = m.next
	m.jobs[j.ID] = &j
	return j.ID, nil
}

func (m *Mem) GetJob(_ context.Context, id int64) (store.Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	j, ok := m.jobs[id]
	if !ok {
		return store.Job{}, store.ErrNotFound
	}
	return *j, nil
}

func (m *Mem) ListJobs(_ context.Context, limit int) ([]store.Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []store.Job{}
	for id := m.next; id >= 1 && len(out) < limit; id-- {
		if j, ok := m.jobs[id]; ok {
			out = append(out, *j)
		}
	}
	return out, nil
}

func (m *Mem) MarkJobRunning(_ context.Context, id int64, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.jobs[id].Status, m.jobs[id].StartedAt = "running", &now
	return nil
}

func (m *Mem) FinishJob(_ context.Context, id int64, status, errText string, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.jobs[id].Status, m.jobs[id].Error, m.jobs[id].FinishedAt = status, errText, &now
	return nil
}

func (m *Mem) InterruptUnfinished(_ context.Context, now time.Time) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var n int64
	for _, j := range m.jobs {
		if j.Status == "queued" || j.Status == "running" {
			j.Status, j.FinishedAt = "interrupted", &now
			n++
		}
	}
	return n, nil
}

func (m *Mem) AddLog(_ context.Context, id int64, at time.Time, line string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs[id] = append(m.logs[id], store.LogLine{ID: int64(len(m.logs[id]) + 1), At: at, Line: line})
	return nil
}

func (m *Mem) JobLogs(_ context.Context, id, after int64, limit int) ([]store.LogLine, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []store.LogLine{}
	for _, l := range m.logs[id] {
		if l.ID > after && len(out) < limit {
			out = append(out, l)
		}
	}
	return out, nil
}

func (m *Mem) PruneJobs(context.Context, int) error { return nil }

// Status and Lines are for assertions.
func (m *Mem) Status(id int64) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.jobs[id].Status
}

func (m *Mem) Lines(id int64) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []string{}
	for _, l := range m.logs[id] {
		out = append(out, l.Line)
	}
	return out
}
