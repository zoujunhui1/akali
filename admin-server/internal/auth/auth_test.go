package auth_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/authtest"
	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

const pw = "correct horse battery"

func setup(t *testing.T) (*auth.Service, *time.Time) {
	t.Helper()
	svc := auth.NewWithCost(authtest.New(), 4)
	now := time.Date(2026, 1, 1, 9, 0, 0, 0, time.UTC)
	svc.SetClock(func() time.Time { return now })
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	return svc, &now
}

func TestLoginGivesASessionThatLogoutEnds(t *testing.T) {
	svc, _ := setup(t)
	ctx := context.Background()
	token, err := svc.Login(ctx, "owner", pw, "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if u, err := svc.User(ctx, token); err != nil || u != "owner" {
		t.Fatalf("user = %q, %v", u, err)
	}
	if err := svc.Logout(ctx, token); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.User(ctx, token); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("after logout: %v", err)
	}
}

func TestSessionExpires(t *testing.T) {
	svc, now := setup(t)
	token, _ := svc.Login(context.Background(), "owner", pw, "1.1.1.1")
	*now = now.Add(auth.SessionTTL + time.Minute)
	if _, err := svc.User(context.Background(), token); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expired session still works: %v", err)
	}
}

func TestWrongPasswordAndUnknownUserLookTheSame(t *testing.T) {
	svc, _ := setup(t)
	if _, err := svc.Login(context.Background(), "owner", "nope nope nope", "1.1.1.1"); !errors.Is(err, auth.ErrBadLogin) {
		t.Fatalf("wrong password: %v", err)
	}
	if _, err := svc.Login(context.Background(), "someone", pw, "1.1.1.1"); !errors.Is(err, auth.ErrBadLogin) {
		t.Fatalf("unknown user: %v", err)
	}
}

func TestFiveFailuresLockTheAddressEvenForTheRightPassword(t *testing.T) {
	svc, now := setup(t)
	ctx := context.Background()
	for i := 0; i < 5; i++ {
		svc.Login(ctx, "owner", "wrong wrong wrong", "9.9.9.9")
	}
	if _, err := svc.Login(ctx, "owner", pw, "9.9.9.9"); !errors.Is(err, auth.ErrLocked) {
		t.Fatalf("locked address got in: %v", err)
	}
	if _, err := svc.Login(ctx, "owner", pw, "2.2.2.2"); err != nil {
		t.Fatalf("another address was locked too: %v", err)
	}
	*now = now.Add(16 * time.Minute)
	if _, err := svc.Login(ctx, "owner", pw, "9.9.9.9"); err != nil {
		t.Fatalf("lock did not end: %v", err)
	}
}

func TestSuccessfulLoginResetsTheFailureCount(t *testing.T) {
	svc, _ := setup(t)
	ctx := context.Background()
	for i := 0; i < 4; i++ {
		svc.Login(ctx, "owner", "wrong wrong wrong", "3.3.3.3")
	}
	if _, err := svc.Login(ctx, "owner", pw, "3.3.3.3"); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 4; i++ {
		svc.Login(ctx, "owner", "wrong wrong wrong", "3.3.3.3")
	}
	if _, err := svc.Login(ctx, "owner", pw, "3.3.3.3"); err != nil {
		t.Fatalf("counter was not reset: %v", err)
	}
}

func TestShortPasswordIsRefused(t *testing.T) {
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", "short"); !errors.Is(err, auth.ErrShort) {
		t.Fatalf("got %v", err)
	}
}
