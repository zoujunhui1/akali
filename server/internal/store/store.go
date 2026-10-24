// Package store keeps lessons in MySQL.
//
// Every change gets a new global revision number (meta.rev), so the app can ask
// "what changed since revision N" and drop deleted lessons (kept as tombstones).
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

var ErrNotFound = errors.New("lesson not found")

var migrations = []string{
	`CREATE TABLE IF NOT EXISTS meta (
		id TINYINT PRIMARY KEY,
		rev BIGINT NOT NULL
	) CHARACTER SET utf8mb4`,
	`INSERT IGNORE INTO meta (id, rev) VALUES (1, 0)`,
	`CREATE TABLE IF NOT EXISTS lessons (
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		level VARCHAR(8) NOT NULL,
		number INT NOT NULL,
		title VARCHAR(255) NOT NULL,
		source_url VARCHAR(512) NULL,
		transcript MEDIUMTEXT NOT NULL,
		audio_size BIGINT NOT NULL DEFAULT 0,
		audio_sha256 CHAR(64) NOT NULL DEFAULT '',
		timestamps JSON NULL,
		vocab JSON NULL,
		rev BIGINT NOT NULL,
		deleted TINYINT(1) NOT NULL DEFAULT 0,
		created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
		updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
		UNIQUE KEY uq_level_number (level, number),
		KEY idx_rev (rev)
	) CHARACTER SET utf8mb4`,
}

type Lesson struct {
	Level      string
	Number     int
	Title      string
	SourceURL  *string
	Transcript string
	AudioSize  int64 // 0 = no audio uploaded yet
	AudioSHA   string
	Timestamps json.RawMessage // nil when absent
	Vocab      json.RawMessage // nil when absent
	Rev        int64
	Deleted    bool
	UpdatedAt  time.Time
}

func (l Lesson) Code() string { return Code(l.Level, l.Number) }

func Code(level string, number int) string { return fmt.Sprintf("%s-%03d", level, number) }

// ParseCode splits "B2-036" into its level and number.
func ParseCode(code string) (level string, number int, err error) {
	i := strings.LastIndex(code, "-")
	if i <= 0 {
		return "", 0, fmt.Errorf("bad lesson code %q", code)
	}
	if _, err := fmt.Sscanf(code[i+1:], "%d", &number); err != nil || number <= 0 {
		return "", 0, fmt.Errorf("bad lesson code %q", code)
	}
	level = strings.ToUpper(code[:i])
	if level == "" || len(level) > 8 {
		return "", 0, fmt.Errorf("bad lesson code %q", code)
	}
	return level, number, nil
}

type Store struct{ db *sql.DB }

// Open connects and creates missing tables.
func Open(dsn string) (*Store, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	db.SetConnMaxLifetime(5 * time.Minute)
	if err := db.Ping(); err != nil {
		return nil, err
	}
	for _, m := range migrations {
		if _, err := db.Exec(m); err != nil {
			return nil, fmt.Errorf("migrate: %w", err)
		}
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// Reset empties all data. Tests only.
func (s *Store) Reset(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `DELETE FROM lessons`); err != nil {
		return err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE meta SET rev = 0`)
	return err
}

// change runs fn inside a transaction with a freshly bumped revision.
func (s *Store) change(ctx context.Context, fn func(tx *sql.Tx, rev int64) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var rev int64
	if _, err := tx.ExecContext(ctx, `UPDATE meta SET rev = rev + 1 WHERE id = 1`); err != nil {
		return err
	}
	if err := tx.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return err
	}
	if err := fn(tx, rev); err != nil {
		return err
	}
	return tx.Commit()
}

func rowsAffectedOrNotFound(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// Upsert creates the lesson, or updates title / link / transcript (and revives a deleted one).
func (s *Store) Upsert(ctx context.Context, level string, number int, title string, sourceURL *string, transcript string) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO lessons (level, number, title, source_url, transcript, rev)
			VALUES (?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE title = VALUES(title), source_url = VALUES(source_url),
				transcript = VALUES(transcript), rev = VALUES(rev), deleted = 0`,
			level, number, title, sourceURL, transcript, rev)
		return err
	})
}

func (s *Store) SetAudio(ctx context.Context, level string, number int, size int64, sha string) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx,
			`UPDATE lessons SET audio_size = ?, audio_sha256 = ?, rev = ? WHERE level = ? AND number = ? AND deleted = 0`,
			size, sha, rev, level, number))
	})
}

func (s *Store) SetTimestamps(ctx context.Context, level string, number int, doc json.RawMessage) error {
	return s.setJSON(ctx, "timestamps", level, number, doc)
}

func (s *Store) SetVocab(ctx context.Context, level string, number int, doc json.RawMessage) error {
	return s.setJSON(ctx, "vocab", level, number, doc)
}

func (s *Store) setJSON(ctx context.Context, column, level string, number int, doc json.RawMessage) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx,
			`UPDATE lessons SET `+column+` = ?, rev = ? WHERE level = ? AND number = ? AND deleted = 0`,
			string(doc), rev, level, number))
	})
}

// Delete keeps a tombstone so apps that synced earlier learn about the deletion.
func (s *Store) Delete(ctx context.Context, level string, number int) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx, `
			UPDATE lessons SET deleted = 1, rev = ?, audio_size = 0, audio_sha256 = '',
				transcript = '', timestamps = NULL, vocab = NULL
			WHERE level = ? AND number = ? AND deleted = 0`, rev, level, number))
	})
}

const lessonColumns = `level, number, title, source_url, transcript, audio_size, audio_sha256,
	timestamps, vocab, rev, deleted, updated_at`

func scan(row interface{ Scan(...any) error }) (Lesson, error) {
	var l Lesson
	var src sql.NullString
	var ts, vocab []byte
	if err := row.Scan(&l.Level, &l.Number, &l.Title, &src, &l.Transcript, &l.AudioSize, &l.AudioSHA,
		&ts, &vocab, &l.Rev, &l.Deleted, &l.UpdatedAt); err != nil {
		return Lesson{}, err
	}
	if src.Valid {
		l.SourceURL = &src.String
	}
	if len(ts) > 0 {
		l.Timestamps = ts
	}
	if len(vocab) > 0 {
		l.Vocab = vocab
	}
	return l, nil
}

func (s *Store) Get(ctx context.Context, level string, number int) (Lesson, error) {
	l, err := scan(s.db.QueryRowContext(ctx,
		`SELECT `+lessonColumns+` FROM lessons WHERE level = ? AND number = ? AND deleted = 0`, level, number))
	if errors.Is(err, sql.ErrNoRows) {
		return Lesson{}, ErrNotFound
	}
	return l, err
}

// Changes returns every lesson whose revision is above since (deleted ones too, unless since is 0),
// together with the current revision.
func (s *Store) Changes(ctx context.Context, since int64) ([]Lesson, int64, error) {
	var rev int64
	if err := s.db.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return nil, 0, err
	}
	q := `SELECT ` + lessonColumns + ` FROM lessons WHERE rev > ?`
	if since == 0 {
		q += ` AND deleted = 0`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY level, number`, since)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []Lesson
	for rows.Next() {
		l, err := scan(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, l)
	}
	return out, rev, rows.Err()
}
