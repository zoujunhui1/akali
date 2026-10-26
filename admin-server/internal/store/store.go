// Package store is the admin system's own MySQL database (akali_admin).
// It never touches the phone-facing database; content goes to that one through the backend's /admin API.
package store

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

var ErrNotFound = errors.New("not found")

type Store struct{ db *sql.DB }

// Open connects and applies any migration that has not run yet.
func Open(dsn string) (*Store, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	s := &Store{db: db}
	if err := s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		name VARCHAR(100) PRIMARY KEY,
		applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
	) CHARACTER SET utf8mb4`); err != nil {
		return err
	}
	names, err := fs.Glob(migrationFiles, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		var done int
		if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations WHERE name = ?`, name).Scan(&done); err != nil {
			return err
		}
		if done > 0 {
			continue
		}
		body, err := migrationFiles.ReadFile(name)
		if err != nil {
			return err
		}
		for _, stmt := range strings.Split(string(body), ";") {
			if strings.TrimSpace(stmt) == "" {
				continue
			}
			if _, err := s.db.ExecContext(ctx, stmt); err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
		}
		if _, err := s.db.ExecContext(ctx, `INSERT INTO schema_migrations (name) VALUES (?)`, name); err != nil {
			return err
		}
	}
	return nil
}

func utc(t time.Time) time.Time { return t.UTC().Truncate(time.Millisecond) }

func (s *Store) SetUser(ctx context.Context, username, hash string) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO admin_user (username, password_hash) VALUES (?, ?)
		ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash)`, username, hash)
	return err
}

func (s *Store) UserHash(ctx context.Context, username string) (string, error) {
	var h string
	err := s.db.QueryRowContext(ctx, `SELECT password_hash FROM admin_user WHERE username = ?`, username).Scan(&h)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return h, err
}

func (s *Store) CreateSession(ctx context.Context, id, username, ip string, created, expires time.Time) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO sessions (id, username, ip, created_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
		id, username, ip, utc(created), utc(expires))
	return err
}

// SessionUser returns the user a live session belongs to; expired or unknown sessions are ErrNotFound.
func (s *Store) SessionUser(ctx context.Context, id string, now time.Time) (string, error) {
	var u string
	err := s.db.QueryRowContext(ctx, `SELECT username FROM sessions WHERE id = ? AND expires_at > ?`, id, utc(now)).Scan(&u)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return u, err
}

func (s *Store) DeleteSession(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE id = ?`, id)
	return err
}

func (s *Store) DeleteExpiredSessions(ctx context.Context, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE expires_at <= ?`, utc(now))
	return err
}

// LockedUntil returns when key's lock ends; the zero time means it is not locked.
func (s *Store) LockedUntil(ctx context.Context, key string, now time.Time) (time.Time, error) {
	var until sql.NullTime
	err := s.db.QueryRowContext(ctx, `SELECT locked_until FROM login_attempts WHERE key_name = ?`, key).Scan(&until)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && (!until.Valid || !until.Time.After(utc(now)))) {
		return time.Time{}, nil
	}
	return until.Time, err
}

// RecordFailure counts a failed login for key and, at maxFailures, locks it for lockFor.
func (s *Store) RecordFailure(ctx context.Context, key string, now time.Time, maxFailures int, lockFor time.Duration) error {
	now = utc(now)
	_, err := s.db.ExecContext(ctx, `INSERT INTO login_attempts (key_name, failures, locked_until, last_failure)
		VALUES (?, 1, NULL, ?)
		ON DUPLICATE KEY UPDATE
			failures = IF(locked_until IS NOT NULL AND locked_until <= ?, 1, failures + 1),
			locked_until = IF(locked_until IS NOT NULL AND locked_until <= ?, NULL, locked_until),
			last_failure = VALUES(last_failure)`, key, now, now, now)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `UPDATE login_attempts SET locked_until = ? WHERE key_name = ? AND failures >= ? AND locked_until IS NULL`,
		utc(now.Add(lockFor)), key, maxFailures)
	return err
}

func (s *Store) ClearFailures(ctx context.Context, key string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM login_attempts WHERE key_name = ?`, key)
	return err
}

// DropAllForTest removes every table this package creates, so a test leaves the database as it found it.
func (s *Store) DropAllForTest(ctx context.Context) {
	for _, t := range []string{"job_logs", "jobs", "login_attempts", "sessions", "admin_user", "schema_migrations"} {
		s.db.ExecContext(ctx, "DROP TABLE IF EXISTS "+t)
	}
}
