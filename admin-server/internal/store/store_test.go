package store_test

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

// Runs against a throw-away local database; set ADMIN_TEST_DSN to enable. The tables are dropped afterwards.
func open(t *testing.T) *store.Store {
	t.Helper()
	dsn := os.Getenv("ADMIN_TEST_DSN")
	if dsn == "" {
		t.Skip("ADMIN_TEST_DSN not set")
	}
	st, err := store.Open(dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		st.DropAllForTest(context.Background())
		st.Close()
	})
	return st
}

func TestOpeningTwiceKeepsTheData(t *testing.T) {
	st := open(t)
	ctx := context.Background()
	if err := st.SetUser(ctx, "owner", "hash1"); err != nil {
		t.Fatal(err)
	}
	st2, err := store.Open(os.Getenv("ADMIN_TEST_DSN"))
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	if h, err := st2.UserHash(ctx, "owner"); err != nil || h != "hash1" {
		t.Fatalf("%q %v", h, err)
	}
}

func TestSessionsExpire(t *testing.T) {
	st := open(t)
	ctx := context.Background()
	now := time.Now()
	st.CreateSession(ctx, "s1", "owner", "1.1.1.1", now, now.Add(time.Hour))
	if u, err := st.SessionUser(ctx, "s1", now); err != nil || u != "owner" {
		t.Fatalf("%q %v", u, err)
	}
	if _, err := st.SessionUser(ctx, "s1", now.Add(2*time.Hour)); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expired session found: %v", err)
	}
	st.DeleteExpiredSessions(ctx, now.Add(2*time.Hour))
	if _, err := st.SessionUser(ctx, "s1", now); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expired session not deleted: %v", err)
	}
}

func TestLockAfterFailuresThenEndsThenClears(t *testing.T) {
	st := open(t)
	ctx := context.Background()
	now := time.Now()
	for i := 0; i < 5; i++ {
		if err := st.RecordFailure(ctx, "ip:1", now, 5, 15*time.Minute); err != nil {
			t.Fatal(err)
		}
	}
	if until, _ := st.LockedUntil(ctx, "ip:1", now); until.IsZero() {
		t.Fatal("not locked after 5 failures")
	}
	if until, _ := st.LockedUntil(ctx, "ip:1", now.Add(16*time.Minute)); !until.IsZero() {
		t.Fatal("lock did not end")
	}
	// a failure after the lock ended starts counting again instead of re-locking at once
	st.RecordFailure(ctx, "ip:1", now.Add(16*time.Minute), 5, 15*time.Minute)
	if until, _ := st.LockedUntil(ctx, "ip:1", now.Add(16*time.Minute)); !until.IsZero() {
		t.Fatal("re-locked after one failure")
	}
	st.ClearFailures(ctx, "ip:1")
	if until, _ := st.LockedUntil(ctx, "ip:1", now); !until.IsZero() {
		t.Fatal("still locked after clearing")
	}
}

func TestJobsLogsAndPruning(t *testing.T) {
	st := open(t)
	ctx := context.Background()
	now := time.Now()
	id, err := st.CreateJob(ctx, store.Job{Kind: "k", Title: "first", Status: "queued", CreatedAt: now, Params: []byte(`{"a":1}`)})
	if err != nil {
		t.Fatal(err)
	}
	st.MarkJobRunning(ctx, id, now)
	st.AddLog(ctx, id, now, "line one")
	st.AddLog(ctx, id, now, "line two")
	logs, _ := st.JobLogs(ctx, id, 0, 10)
	if len(logs) != 2 || logs[0].Line != "line one" {
		t.Fatalf("logs = %+v", logs)
	}
	if rest, _ := st.JobLogs(ctx, id, logs[0].ID, 10); len(rest) != 1 || rest[0].Line != "line two" {
		t.Fatalf("logs after the first = %+v", rest)
	}
	st.FinishJob(ctx, id, "failed", "reason", now)
	j, err := st.GetJob(ctx, id)
	if err != nil || j.Status != "failed" || j.Error != "reason" || j.StartedAt == nil || j.FinishedAt == nil || string(j.Params) != `{"a": 1}` {
		t.Fatalf("job = %+v, %v", j, err)
	}
	if _, err := st.GetJob(ctx, 99999); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("missing job: %v", err)
	}

	// pruning keeps the newest and spares unfinished jobs
	for i := 0; i < 4; i++ {
		st.CreateJob(ctx, store.Job{Kind: "k", Title: "later", Status: "succeeded", CreatedAt: now})
	}
	st.CreateJob(ctx, store.Job{Kind: "k", Title: "running", Status: "running", CreatedAt: now})
	if err := st.PruneJobs(ctx, 2); err != nil {
		t.Fatal(err)
	}
	list, _ := st.ListJobs(ctx, 50)
	if len(list) != 2 || list[0].Title != "running" || list[1].Title != "later" {
		titles := []string{}
		for _, j := range list {
			titles = append(titles, j.Title+":"+j.Status)
		}
		t.Fatalf("after pruning: %v", titles)
	}
	if left, _ := st.JobLogs(ctx, id, 0, 10); len(left) != 0 {
		t.Fatal("the pruned job's log was kept")
	}
}

func TestInterruptUnfinished(t *testing.T) {
	st := open(t)
	ctx := context.Background()
	now := time.Now()
	a, _ := st.CreateJob(ctx, store.Job{Kind: "k", Title: "a", Status: "running", CreatedAt: now})
	b, _ := st.CreateJob(ctx, store.Job{Kind: "k", Title: "b", Status: "succeeded", CreatedAt: now})
	n, err := st.InterruptUnfinished(ctx, now)
	if err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	ja, _ := st.GetJob(ctx, a)
	jb, _ := st.GetJob(ctx, b)
	if ja.Status != "interrupted" || jb.Status != "succeeded" {
		t.Fatalf("%s %s", ja.Status, jb.Status)
	}
}
