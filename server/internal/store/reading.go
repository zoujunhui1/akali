package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"regexp"
	"time"
)

// Articles for the reading feature and the learner's word notebook. Both share the revision counter
// with lessons and vocabulary, so "changes since N" works the same way for everything.
func init() { migrations = append(migrations, readingMigrations...) }

var readingMigrations = []string{
	`CREATE TABLE IF NOT EXISTS articles (
		code VARCHAR(16) NOT NULL PRIMARY KEY,
		title VARCHAR(255) NOT NULL,
		source VARCHAR(512) NOT NULL DEFAULT '',
		body JSON NOT NULL,
		word_count INT NOT NULL DEFAULT 0,
		rev BIGINT NOT NULL,
		deleted TINYINT(1) NOT NULL DEFAULT 0,
		created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
		KEY idx_articles_rev (rev)
	) CHARACTER SET utf8mb4`,
	`CREATE TABLE IF NOT EXISTS notebook (
		id VARCHAR(40) NOT NULL PRIMARY KEY,
		word VARCHAR(64) NOT NULL,
		sentence TEXT NOT NULL,
		article VARCHAR(16) NOT NULL DEFAULT '',
		meaning VARCHAR(500) NOT NULL DEFAULT '',
		state VARCHAR(16) NOT NULL,
		rev BIGINT NOT NULL,
		KEY idx_notebook_rev (rev)
	) CHARACTER SET utf8mb4`,
}

var articleCode = regexp.MustCompile(`^R[0-9]{4,6}$`)
var notebookID = regexp.MustCompile(`^[0-9A-Za-z-]{8,40}$`)

// ValidArticleCode reports whether code looks like "R0007".
func ValidArticleCode(code string) bool { return articleCode.MatchString(code) }

// ValidNotebookID reports whether id looks like an id the app made (a UUID).
func ValidNotebookID(id string) bool { return notebookID.MatchString(id) }

// ValidNotebookState: "learning", "mastered", or "none" (a tombstone for a removed word).
func ValidNotebookState(s string) bool { return s == "learning" || s == "mastered" || s == "none" }

type Article struct {
	Code      string
	Title     string
	Source    string
	Body      json.RawMessage // [[sentence, ...], ...] one list of sentences per paragraph
	WordCount int
	Rev       int64
	Deleted   bool
	CreatedAt time.Time
}

// PutArticle creates or replaces an article under a new revision.
func (s *Store) PutArticle(ctx context.Context, a Article) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO articles (code, title, source, body, word_count, rev, deleted)
			VALUES (?, ?, ?, ?, ?, ?, 0)
			ON DUPLICATE KEY UPDATE title = VALUES(title), source = VALUES(source), body = VALUES(body),
				word_count = VALUES(word_count), rev = VALUES(rev), deleted = 0`,
			a.Code, a.Title, a.Source, []byte(a.Body), a.WordCount, rev)
		return err
	})
}

// DeleteArticle leaves a tombstone so phones drop the article at their next sync.
func (s *Store) DeleteArticle(ctx context.Context, code string) error {
	return s.change(ctx, func(tx *sql.Tx, rev int64) error {
		res, err := tx.ExecContext(ctx, `UPDATE articles SET deleted = 1, rev = ? WHERE code = ?`, rev, code)
		return rowsAffectedOrNotFound(res, err)
	})
}

// ArticleChanges returns the articles changed after revision since and the current revision.
// A first sync (since 0) leaves out deleted ones.
func (s *Store) ArticleChanges(ctx context.Context, since int64) ([]Article, int64, error) {
	var rev int64
	if err := s.db.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return nil, 0, err
	}
	q := `SELECT code, title, source, body, word_count, rev, deleted, created_at FROM articles WHERE rev > ?`
	if since == 0 {
		q += ` AND deleted = 0`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY code`, since)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []Article
	for rows.Next() {
		var a Article
		var body []byte
		if err := rows.Scan(&a.Code, &a.Title, &a.Source, &body, &a.WordCount, &a.Rev, &a.Deleted, &a.CreatedAt); err != nil {
			return nil, 0, err
		}
		a.Body = json.RawMessage(body)
		out = append(out, a)
	}
	return out, rev, rows.Err()
}

type NotebookWord struct {
	ID       string
	Word     string
	Sentence string
	Article  string
	Meaning  string
	State    string // "learning", "mastered" or "none"
	Rev      int64
}

// SetNotebook saves all words under one new revision and returns it. The last one for an id wins.
func (s *Store) SetNotebook(ctx context.Context, words []NotebookWord) (int64, error) {
	if len(words) == 0 {
		return 0, errors.New("no words given")
	}
	var rev int64
	err := s.change(ctx, func(tx *sql.Tx, r int64) error {
		for _, w := range words {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO notebook (id, word, sentence, article, meaning, state, rev) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE word = VALUES(word), sentence = VALUES(sentence), article = VALUES(article),
					meaning = VALUES(meaning), state = VALUES(state), rev = VALUES(rev)`,
				w.ID, w.Word, w.Sentence, w.Article, w.Meaning, w.State, r); err != nil {
				return err
			}
		}
		rev = r
		return nil
	})
	return rev, err
}

// NotebookChanges returns the words changed after revision since (removed ones too, unless since is 0).
func (s *Store) NotebookChanges(ctx context.Context, since int64) ([]NotebookWord, int64, error) {
	var rev int64
	if err := s.db.QueryRowContext(ctx, `SELECT rev FROM meta WHERE id = 1`).Scan(&rev); err != nil {
		return nil, 0, err
	}
	q := `SELECT id, word, sentence, article, meaning, state, rev FROM notebook WHERE rev > ?`
	if since == 0 {
		q += ` AND state <> 'none'`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY rev, id`, since)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []NotebookWord
	for rows.Next() {
		var w NotebookWord
		if err := rows.Scan(&w.ID, &w.Word, &w.Sentence, &w.Article, &w.Meaning, &w.State, &w.Rev); err != nil {
			return nil, 0, err
		}
		out = append(out, w)
	}
	return out, rev, rows.Err()
}
