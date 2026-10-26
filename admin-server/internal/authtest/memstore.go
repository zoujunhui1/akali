// Package authtest has an in-memory auth.Store so the login can be tested without a database.
package authtest

import (
	"context"
	"sync"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

type attempt struct {
	failures int
	locked   time.Time
}

type Mem struct {
	mu       sync.Mutex
	users    map[string]string
	sessions map[string]struct {
		user    string
		expires time.Time
	}
	attempts map[string]*attempt
}

func New() *Mem {
	return &Mem{users: map[string]string{}, attempts: map[string]*attempt{},
		sessions: map[string]struct {
			user    string
			expires time.Time
		}{}}
}

func (m *Mem) SetUser(_ context.Context, u, h string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.users[u] = h
	return nil
}

func (m *Mem) UserHash(_ context.Context, u string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	h, ok := m.users[u]
	if !ok {
		return "", store.ErrNotFound
	}
	return h, nil
}

func (m *Mem) CreateSession(_ context.Context, id, u, _ string, _, expires time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sessions[id] = struct {
		user    string
		expires time.Time
	}{u, expires}
	return nil
}

func (m *Mem) SessionUser(_ context.Context, id string, now time.Time) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.sessions[id]
	if !ok || !s.expires.After(now) {
		return "", store.ErrNotFound
	}
	return s.user, nil
}

func (m *Mem) DeleteSession(_ context.Context, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sessions, id)
	return nil
}

func (m *Mem) DeleteExpiredSessions(_ context.Context, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, s := range m.sessions {
		if !s.expires.After(now) {
			delete(m.sessions, id)
		}
	}
	return nil
}

func (m *Mem) LockedUntil(_ context.Context, key string, now time.Time) (time.Time, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if a := m.attempts[key]; a != nil && a.locked.After(now) {
		return a.locked, nil
	}
	return time.Time{}, nil
}

func (m *Mem) RecordFailure(_ context.Context, key string, now time.Time, max int, lockFor time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	a := m.attempts[key]
	if a == nil || (!a.locked.IsZero() && !a.locked.After(now)) {
		a = &attempt{}
		m.attempts[key] = a
	}
	a.failures++
	if a.failures >= max && a.locked.IsZero() {
		a.locked = now.Add(lockFor)
	}
	return nil
}

func (m *Mem) ClearFailures(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.attempts, key)
	return nil
}
