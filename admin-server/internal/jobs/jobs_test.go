package jobs_test

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/jobstest"
	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

type mem = jobstest.Mem

func newMem() *mem { return jobstest.New() }

func waitStatus(t *testing.T, m *mem, id int64, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if m.Status(id) == want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("job %d: status %q, wanted %q", id, m.Status(id), want)
}

func submit(t *testing.T, r *jobs.Runner, heavy bool, fn jobs.Func) int64 {
	t.Helper()
	id, err := r.Submit(context.Background(), jobs.Spec{Kind: "test", Title: "t", Heavy: heavy}, fn)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestASuccessfulJobKeepsItsLogAndTimes(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(ctx context.Context, run *jobs.Run) error {
		run.Log("one")
		run.Log("two")
		return nil
	})
	waitStatus(t, m, id, jobs.Succeeded)
	j, _ := m.GetJob(context.Background(), id)
	if j.StartedAt == nil || j.FinishedAt == nil {
		t.Fatal("times not recorded")
	}
	if got := strings.Join(m.Lines(id), ","); got != "one,two" {
		t.Fatalf("log = %q", got)
	}
}

func TestAFailedJobKeepsTheReason(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(context.Context, *jobs.Run) error { return errors.New("the page was empty") })
	waitStatus(t, m, id, jobs.Failed)
	if j, _ := m.GetJob(context.Background(), id); j.Error != "the page was empty" {
		t.Fatalf("error = %q", j.Error)
	}
}

func TestAPanicIsAFailureNotACrash(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(context.Context, *jobs.Run) error { panic("boom") })
	waitStatus(t, m, id, jobs.Failed)
}

func TestLongLinesAreCut(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(_ context.Context, run *jobs.Run) error {
		run.Log(strings.Repeat("字", 5000))
		return nil
	})
	waitStatus(t, m, id, jobs.Succeeded)
	if n := len([]rune(m.Lines(id)[0])); n != 2000 {
		t.Fatalf("line has %d characters", n)
	}
}

// peak runs n jobs of the given weight that each hold their slot briefly, and returns how many ran at once at most.
func peak(t *testing.T, heavy bool, n int) int32 {
	m := newMem()
	r := jobs.New(context.Background(), m)
	var now, max int32
	ids := make([]int64, n)
	for i := range ids {
		ids[i] = submit(t, r, heavy, func(context.Context, *jobs.Run) error {
			c := atomic.AddInt32(&now, 1)
			for {
				old := atomic.LoadInt32(&max)
				if c <= old || atomic.CompareAndSwapInt32(&max, old, c) {
					break
				}
			}
			time.Sleep(30 * time.Millisecond)
			atomic.AddInt32(&now, -1)
			return nil
		})
	}
	for _, id := range ids {
		waitStatus(t, m, id, jobs.Succeeded)
	}
	return atomic.LoadInt32(&max)
}

func TestHeavyJobsRunOneAtATime(t *testing.T) {
	if p := peak(t, true, 4); p != 1 {
		t.Fatalf("%d heavy jobs ran at once", p)
	}
}

func TestLightJobsRunUpToThreeAtATime(t *testing.T) {
	if p := peak(t, false, 8); p < 2 || p > 3 {
		t.Fatalf("%d light jobs ran at once, want 2 or 3", p)
	}
}

func TestCancellingARunningJob(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	started := make(chan struct{})
	id := submit(t, r, false, func(ctx context.Context, _ *jobs.Run) error {
		close(started)
		<-ctx.Done()
		return ctx.Err()
	})
	<-started
	if err := r.Cancel(id); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, m, id, jobs.Cancelled)
	if err := r.Cancel(id); !errors.Is(err, jobs.ErrFinished) {
		t.Fatalf("cancelling a finished job: %v", err)
	}
}

func TestCancellingAQueuedJobNeverRunsIt(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	release := make(chan struct{})
	started := make(chan struct{})
	first := submit(t, r, true, func(context.Context, *jobs.Run) error { close(started); <-release; return nil })
	<-started
	var ran atomic.Bool
	second := submit(t, r, true, func(context.Context, *jobs.Run) error { ran.Store(true); return nil })
	waitStatus(t, m, second, jobs.Queued)
	if err := r.Cancel(second); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, m, second, jobs.Cancelled)
	close(release)
	waitStatus(t, m, first, jobs.Succeeded)
	if ran.Load() {
		t.Fatal("cancelled job ran")
	}
}

func TestCancellingAnUnknownJob(t *testing.T) {
	r := jobs.New(context.Background(), newMem())
	if err := r.Cancel(999); !errors.Is(err, jobs.ErrNotFound) {
		t.Fatalf("got %v", err)
	}
}

func TestShuttingDownInterruptsRunningJobs(t *testing.T) {
	m := newMem()
	base, stop := context.WithCancel(context.Background())
	r := jobs.New(base, m)
	started := make(chan struct{})
	id := submit(t, r, false, func(ctx context.Context, _ *jobs.Run) error {
		close(started)
		<-ctx.Done()
		return ctx.Err()
	})
	<-started
	stop()
	waitStatus(t, m, id, jobs.Interrupted)
	r.Wait()
}

func TestStartupMarksLeftoverJobsInterrupted(t *testing.T) {
	m := newMem()
	m.CreateJob(context.Background(), store.Job{Status: jobs.Running})
	m.CreateJob(context.Background(), store.Job{Status: jobs.Queued})
	m.CreateJob(context.Background(), store.Job{Status: jobs.Succeeded})
	if err := jobs.New(context.Background(), m).Recover(context.Background()); err != nil {
		t.Fatal(err)
	}
	if m.Status(1) != jobs.Interrupted || m.Status(2) != jobs.Interrupted || m.Status(3) != jobs.Succeeded {
		t.Fatalf("statuses: %s %s %s", m.Status(1), m.Status(2), m.Status(3))
	}
}

func TestRunCommandLogsEveryLineIncludingErrors(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(ctx context.Context, run *jobs.Run) error {
		return jobs.RunCommand(ctx, run, "", "sh", "-c", "echo first; echo second >&2")
	})
	waitStatus(t, m, id, jobs.Succeeded)
	got := strings.Join(m.Lines(id), ",")
	if !strings.Contains(got, "first") || !strings.Contains(got, "second") {
		t.Fatalf("log = %q", got)
	}
}

func TestRunCommandFailureIsAFailedJob(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	id := submit(t, r, false, func(ctx context.Context, run *jobs.Run) error {
		return jobs.RunCommand(ctx, run, "", "sh", "-c", "echo about to fail; exit 3")
	})
	waitStatus(t, m, id, jobs.Failed)
}

func TestCancellingKillsTheScriptAndWhatItStarted(t *testing.T) {
	m := newMem()
	r := jobs.New(context.Background(), m)
	marker := t.TempDir() + "/still-alive"
	id := submit(t, r, false, func(ctx context.Context, run *jobs.Run) error {
		// a shell that starts a child which would write a file after a few seconds
		return jobs.RunCommand(ctx, run, "", "sh", "-c", "echo started; (sleep 2; touch "+marker+") & wait")
	})
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if len(m.Lines(id)) > 0 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	began := time.Now()
	if err := r.Cancel(id); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, m, id, jobs.Cancelled)
	if time.Since(began) > 2*time.Second {
		t.Fatal("cancelling took too long")
	}
	time.Sleep(2500 * time.Millisecond)
	if _, err := statFile(marker); err == nil {
		t.Fatal("the script's child kept running after the cancel")
	}
}
