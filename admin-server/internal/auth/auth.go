// Package auth is the single-user login: bcrypt password, server-side sessions, and a lock after repeated failures.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

const (
	MinPasswordLen = 12
	maxFailures    = 5
	lockFor        = 15 * time.Minute
	SessionTTL     = 7 * 24 * time.Hour
)

var (
	ErrBadLogin = errors.New("wrong username or password")
	ErrLocked   = errors.New("too many failed attempts, try again later")
	ErrShort    = errors.New("password is too short")
)

// Store is what the login needs from the database.
type Store interface {
	SetUser(ctx context.Context, username, hash string) error
	UserHash(ctx context.Context, username string) (string, error)
	CreateSession(ctx context.Context, id, username, ip string, created, expires time.Time) error
	SessionUser(ctx context.Context, id string, now time.Time) (string, error)
	DeleteSession(ctx context.Context, id string) error
	DeleteExpiredSessions(ctx context.Context, now time.Time) error
	LockedUntil(ctx context.Context, key string, now time.Time) (time.Time, error)
	RecordFailure(ctx context.Context, key string, now time.Time, maxFailures int, lockFor time.Duration) error
	ClearFailures(ctx context.Context, key string) error
}

type Service struct {
	store Store
	now   func() time.Time
	cost  int
	// a hash to compare against when the user does not exist, so both cases take the same time
	dummy []byte
}

func New(s Store) *Service { return NewWithCost(s, 12) }

// NewWithCost is New with a chosen bcrypt cost; tests use a low one to stay fast.
func NewWithCost(s Store, cost int) *Service {
	a := &Service{store: s, now: time.Now, cost: cost}
	a.dummy, _ = bcrypt.GenerateFromPassword([]byte("not a real password"), a.cost)
	return a
}

// SetClock replaces the clock, for tests.
func (a *Service) SetClock(now func() time.Time) { a.now = now }

// SetPassword stores a new password for username (creating the user).
func (a *Service) SetPassword(ctx context.Context, username, password string) error {
	if len(password) < MinPasswordLen {
		return ErrShort
	}
	h, err := bcrypt.GenerateFromPassword([]byte(password), a.cost)
	if err != nil {
		return err
	}
	return a.store.SetUser(ctx, username, string(h))
}

// Login checks the credentials and returns a session token (only its hash is stored).
// Failures are counted per client address, so a stranger cannot lock the owner out of another address.
func (a *Service) Login(ctx context.Context, username, password, ip string) (token string, err error) {
	now := a.now()
	key := "ip:" + ip
	if until, err := a.store.LockedUntil(ctx, key, now); err != nil {
		return "", err
	} else if !until.IsZero() {
		return "", ErrLocked
	}

	hash, err := a.store.UserHash(ctx, username)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return "", err
	}
	ok := err == nil
	h := []byte(hash)
	if !ok {
		h = a.dummy
	}
	if bcrypt.CompareHashAndPassword(h, []byte(password)) != nil || !ok {
		if err := a.store.RecordFailure(ctx, key, now, maxFailures, lockFor); err != nil {
			return "", err
		}
		return "", ErrBadLogin
	}
	if err := a.store.ClearFailures(ctx, key); err != nil {
		return "", err
	}

	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token = base64.RawURLEncoding.EncodeToString(raw)
	if err := a.store.CreateSession(ctx, hashToken(token), username, ip, now, now.Add(SessionTTL)); err != nil {
		return "", err
	}
	_ = a.store.DeleteExpiredSessions(ctx, now)
	return token, nil
}

// User returns the user for a session token, or ErrNotFound.
func (a *Service) User(ctx context.Context, token string) (string, error) {
	if token == "" {
		return "", store.ErrNotFound
	}
	return a.store.SessionUser(ctx, hashToken(token), a.now())
}

func (a *Service) Logout(ctx context.Context, token string) error {
	return a.store.DeleteSession(ctx, hashToken(token))
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
