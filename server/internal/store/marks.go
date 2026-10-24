package store

import (
	"context"
	"database/sql"
	"errors"
)

// Marks are the learner's own notes on vocabulary entries: "mastered" or "favorite" (state "none" is
// a tombstone so other devices learn that a mark was removed). They share the revision counter with
// the lessons, so "changes since N" works the same way.
func init() { migrations = append(migrations, marksMigrations...) }

var marksMigrations = []string{
	`CREATE TABLE IF NOT EXISTS vocab_marks (
		code VARCHAR(16) NOT NULL,
		entry_key VARCHAR(16) NOT NULL,
		state VARCHAR(16) NOT NULL,
		rev BIGINT NOT NULL,
		PRIMARY KEY (code, entry_key),
		KEY idx_marks_rev (rev)
	) CHARACTER SET utf8mb4`,
}

type Mark struct {
	Code  string
	Key   string
	State string // "mastered", "favorite" or "none"
	Rev   int64
}

// ValidMarkKey reports whether key looks like an entry key such as "9-A".
func ValidMarkKey(key string) bool { return noteKey.MatchString(key) }

// ValidMarkState reports whether state is one the app may send.
func ValidMarkState(state string) bool {
	return state == "mastered" || state == "favorite" || state == "none"
}

// SetMarks saves all marks under one new revision and returns it. The last mark for a key wins.
func (s *Store) SetMarks(ctx context.Context, marks []Mark) (int64, error) {
	if len(marks) == 0 {
		return 0, errors.New("no marks given")
	}
	var rev int64
	err := s.change(ctx, func(tx *sql.Tx, r int64) error {
		for _, m := range marks {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO vocab_marks (code, entry_key, state, rev) VALUES (?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE state = VALUES(state), rev = VALUES(rev)`,
				m.Code, m.Key, m.State, r); err != nil {
				return err
			}
		}
		rev = r
		return nil
	})
	return rev, err
}

// MarkChanges returns the marks changed after revision since (removed ones too, unless since is 0)
// and the current revision.
func (s *Store) MarkChanges(ctx context.Context, since int64) ([]Mark, int64, error) {
	var rev int64
	if err := s.db.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return nil, 0, err
	}
	q := `SELECT code, entry_key, state, rev FROM vocab_marks WHERE rev > ?`
	if since == 0 {
		q += ` AND state <> 'none'`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY code, entry_key`, since)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []Mark
	for rows.Next() {
		var m Mark
		if err := rows.Scan(&m.Code, &m.Key, &m.State, &m.Rev); err != nil {
			return nil, 0, err
		}
		out = append(out, m)
	}
	return out, rev, rows.Err()
}
