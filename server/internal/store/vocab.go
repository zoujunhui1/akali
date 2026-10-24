package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"time"
)

// Vocabulary lessons (picture dictionary topics) live in their own table but share the revision
// counter with the listening lessons, so one "changes since N" scheme covers both.
var vocabMigrations = []string{
	`CREATE TABLE IF NOT EXISTS vocab_lessons (
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		unit INT NOT NULL,
		number INT NOT NULL,
		title VARCHAR(255) NOT NULL,
		pages JSON NOT NULL,
		entries JSON NOT NULL,
		notes JSON NULL,
		bundle_size BIGINT NOT NULL DEFAULT 0,
		bundle_sha256 CHAR(64) NOT NULL DEFAULT '',
		rev BIGINT NOT NULL,
		deleted TINYINT(1) NOT NULL DEFAULT 0,
		created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
		UNIQUE KEY uq_unit_number (unit, number),
		KEY idx_vocab_rev (rev)
	) CHARACTER SET utf8mb4`,
}

func init() { migrations = append(migrations, vocabMigrations...) }

// vocabAlters add columns to tables made by an older version; "duplicate column" means already done.
var vocabAlters = []string{`ALTER TABLE vocab_lessons ADD COLUMN notes JSON NULL AFTER entries`}

type VocabLesson struct {
	Unit       int
	Number     int
	Title      string
	Pages      json.RawMessage
	Entries    json.RawMessage
	Notes      json.RawMessage // entry key -> generated explanation; nil when none yet
	BundleSize int64           // 0 = no bundle uploaded yet
	BundleSHA  string
	Rev        int64
	Deleted    bool
	CreatedAt  time.Time
}

// VocabCode is the public name of a vocabulary lesson: "V01-03" is unit 1, topic 3.
func VocabCode(unit, number int) string { return fmt.Sprintf("V%02d-%02d", unit, number) }

// ParseVocabCode splits "V01-03" into unit and number.
func ParseVocabCode(code string) (unit, number int, err error) {
	level, number, err := ParseCode(code)
	if err != nil || len(level) < 2 || level[0] != 'V' {
		return 0, 0, errors.New("bad vocabulary code " + code)
	}
	for _, c := range level[1:] {
		if c < '0' || c > '9' {
			return 0, 0, errors.New("bad vocabulary code " + code)
		}
		unit = unit*10 + int(c-'0')
	}
	if unit == 0 {
		return 0, 0, errors.New("bad vocabulary code " + code)
	}
	return unit, number, nil
}

func (l VocabLesson) Code() string { return VocabCode(l.Unit, l.Number) }

// UpsertVocab creates the topic or replaces its title, pages and entries (reviving a deleted one).
func (s *Store) UpsertVocab(ctx context.Context, unit, number int, title string, pages, entries json.RawMessage) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO vocab_lessons (unit, number, title, pages, entries, rev)
			VALUES (?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE title = VALUES(title), pages = VALUES(pages), entries = VALUES(entries),
				rev = VALUES(rev), deleted = 0`,
			unit, number, title, string(pages), string(entries), rev)
		return err
	})
}

func (s *Store) SetVocabBundle(ctx context.Context, unit, number int, size int64, sha string) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx,
			`UPDATE vocab_lessons SET bundle_size = ?, bundle_sha256 = ?, rev = ? WHERE unit = ? AND number = ? AND deleted = 0`,
			size, sha, rev, unit, number))
	})
}

func (s *Store) DeleteVocab(ctx context.Context, unit, number int) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx, `
			UPDATE vocab_lessons SET deleted = 1, rev = ?, bundle_size = 0, bundle_sha256 = '',
				pages = JSON_ARRAY(), entries = JSON_ARRAY(), notes = NULL
			WHERE unit = ? AND number = ? AND deleted = 0`, rev, unit, number))
	})
}

const vocabColumns = `unit, number, title, pages, entries, notes, bundle_size, bundle_sha256, rev, deleted, created_at`

func scanVocab(row interface{ Scan(...any) error }) (VocabLesson, error) {
	var l VocabLesson
	var pages, entries, notes []byte
	if err := row.Scan(&l.Unit, &l.Number, &l.Title, &pages, &entries, &notes, &l.BundleSize, &l.BundleSHA,
		&l.Rev, &l.Deleted, &l.CreatedAt); err != nil {
		return VocabLesson{}, err
	}
	l.Pages, l.Entries = pages, entries
	if len(notes) > 0 {
		l.Notes = notes
	}
	return l, nil
}

func (s *Store) GetVocab(ctx context.Context, unit, number int) (VocabLesson, error) {
	l, err := scanVocab(s.db.QueryRowContext(ctx,
		`SELECT `+vocabColumns+` FROM vocab_lessons WHERE unit = ? AND number = ? AND deleted = 0`, unit, number))
	if errors.Is(err, sql.ErrNoRows) {
		return VocabLesson{}, ErrNotFound
	}
	return l, err
}

// VocabChanges returns the topics changed after revision since (deleted ones too, unless since is 0)
// and the current revision.
func (s *Store) VocabChanges(ctx context.Context, since int64) ([]VocabLesson, int64, error) {
	var rev int64
	if err := s.db.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return nil, 0, err
	}
	q := `SELECT ` + vocabColumns + ` FROM vocab_lessons WHERE rev > ?`
	if since == 0 {
		q += ` AND deleted = 0`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY unit, number`, since)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []VocabLesson
	for rows.Next() {
		l, err := scanVocab(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, l)
	}
	return out, rev, rows.Err()
}

var noteKey = regexp.MustCompile(`^[0-9A-Za-z-]{1,16}$`)

// SetVocabNote stores the generated explanation for one entry (key looks like "9-A": page and label).
func (s *Store) SetVocabNote(ctx context.Context, unit, number int, key string, note json.RawMessage) error {
	if !noteKey.MatchString(key) {
		return errors.New("bad note key")
	}
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		return rowsAffectedOrNotFound(tx.ExecContext(ctx,
			`UPDATE vocab_lessons SET notes = JSON_SET(COALESCE(notes, JSON_OBJECT()), '$."`+key+`"', CAST(? AS JSON)), rev = ?
			 WHERE unit = ? AND number = ? AND deleted = 0`,
			string(note), rev, unit, number))
	})
}
