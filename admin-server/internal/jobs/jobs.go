// Package jobs runs the admin system's tasks: it queues them, runs heavy ones one at a time, keeps their
// status and log lines in the database, and can cancel them (killing any script they started).
package jobs

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

const (
	Queued      = "queued"
	Running     = "running"
	Succeeded   = "succeeded"
	Failed      = "failed"
	Cancelled   = "cancelled"
	Interrupted = "interrupted"

	maxLine    = 2000
	keepJobs   = 200
	lightLimit = 3
)

var (
	ErrNotFound = errors.New("job not found")
	ErrFinished = errors.New("job is not queued or running")
)

// Store is what the runner needs from the database.
type Store interface {
	CreateJob(ctx context.Context, j store.Job) (int64, error)
	GetJob(ctx context.Context, id int64) (store.Job, error)
	MarkJobRunning(ctx context.Context, id int64, now time.Time) error
	FinishJob(ctx context.Context, id int64, status, errText string, now time.Time) error
	InterruptUnfinished(ctx context.Context, now time.Time) (int64, error)
	AddLog(ctx context.Context, jobID int64, at time.Time, line string) error
	PruneJobs(ctx context.Context, keep int) error
}

// Spec describes a job to run.
type Spec struct {
	Kind   string
	Title  string
	Heavy  bool // heavy jobs (speech recognition, OCR, voice synthesis) run one at a time
	Params []byte
}

// Func is the work. It must stop soon after ctx is cancelled and may call run.Log as it goes.
type Func func(ctx context.Context, run *Run) error

// Run is what a job's Func uses to report progress.
type Run struct {
	ID int64
	r  *Runner
}

// Log adds one line to the job's log (long lines are cut).
func (r *Run) Log(line string) {
	if utf8.RuneCountInString(line) > maxLine {
		line = string([]rune(line)[:maxLine])
	}
	if err := r.r.store.AddLog(context.Background(), r.ID, r.r.now(), line); err != nil {
		log.Printf("job %d: saving log line: %v", r.ID, err)
	}
}

type entry struct {
	cancel   context.CancelFunc
	byUser   bool
	finished bool
}

type Runner struct {
	store Store
	now   func() time.Time
	base  context.Context

	heavy chan struct{}
	light chan struct{}

	mu      sync.Mutex
	entries map[int64]*entry
	wg      sync.WaitGroup
}

// New makes a runner. Jobs stop with an "interrupted" status when base is cancelled (the program is shutting down).
func New(base context.Context, s Store) *Runner {
	return &Runner{
		store: s, now: time.Now, base: base,
		heavy:   make(chan struct{}, 1),
		light:   make(chan struct{}, lightLimit),
		entries: map[int64]*entry{},
	}
}

// Recover marks jobs a previous run of the program left queued or running as interrupted.
func (r *Runner) Recover(ctx context.Context) error {
	n, err := r.store.InterruptUnfinished(ctx, r.now())
	if n > 0 {
		log.Printf("marked %d unfinished jobs from the last run as interrupted", n)
	}
	return err
}

// Wait blocks until every started job has finished.
func (r *Runner) Wait() { r.wg.Wait() }

// Submit queues a job and returns its id at once.
func (r *Runner) Submit(ctx context.Context, spec Spec, fn Func) (int64, error) {
	id, err := r.store.CreateJob(ctx, store.Job{
		Kind: spec.Kind, Title: spec.Title, Heavy: spec.Heavy, Status: Queued, Params: spec.Params, CreatedAt: r.now(),
	})
	if err != nil {
		return 0, err
	}
	jobCtx, cancel := context.WithCancel(r.base)
	e := &entry{cancel: cancel}
	r.mu.Lock()
	r.entries[id] = e
	r.mu.Unlock()

	r.wg.Add(1)
	go r.run(jobCtx, id, spec.Heavy, e, fn)
	return id, nil
}

func (r *Runner) run(ctx context.Context, id int64, heavy bool, e *entry, fn Func) {
	defer r.wg.Done()
	defer e.cancel()

	slots := r.light
	if heavy {
		slots = r.heavy
	}
	select {
	case slots <- struct{}{}:
		defer func() { <-slots }()
	case <-ctx.Done():
		r.finish(id, e, ctx.Err())
		return
	}
	if ctx.Err() != nil {
		r.finish(id, e, ctx.Err())
		return
	}
	if err := r.store.MarkJobRunning(context.Background(), id, r.now()); err != nil {
		log.Printf("job %d: marking running: %v", id, err)
	}
	r.finish(id, e, r.call(ctx, id, fn))
}

// call runs fn and turns a panic into an error, so one bad job cannot take the program down.
func (r *Runner) call(ctx context.Context, id int64, fn Func) (err error) {
	defer func() {
		if p := recover(); p != nil {
			err = errors.New("job crashed")
			log.Printf("job %d crashed: %v", id, p)
		}
	}()
	return fn(ctx, &Run{ID: id, r: r})
}

func (r *Runner) finish(id int64, e *entry, err error) {
	r.mu.Lock()
	byUser := e.byUser
	e.finished = true
	r.mu.Unlock()

	status, text := Succeeded, ""
	switch {
	case err == nil:
	case byUser:
		status = Cancelled
	case r.base.Err() != nil:
		status = Interrupted
	default:
		status, text = Failed, err.Error()
		if len(text) > 1000 {
			text = text[:1000]
		}
	}
	if err := r.store.FinishJob(context.Background(), id, status, text, r.now()); err != nil {
		log.Printf("job %d: saving result: %v", id, err)
	}
	if err := r.store.PruneJobs(context.Background(), keepJobs); err != nil {
		log.Printf("pruning old jobs: %v", err)
	}
	r.mu.Lock()
	delete(r.entries, id)
	r.mu.Unlock()
}

// Cancel stops a queued or running job.
func (r *Runner) Cancel(id int64) error {
	r.mu.Lock()
	e := r.entries[id]
	if e != nil && !e.finished {
		e.byUser = true
	}
	r.mu.Unlock()
	if e == nil || e.finished {
		if _, err := r.store.GetJob(context.Background(), id); errors.Is(err, store.ErrNotFound) {
			return ErrNotFound
		}
		return ErrFinished
	}
	e.cancel()
	return nil
}
