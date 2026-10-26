package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

type Job struct {
	ID         int64           `json:"id"`
	Kind       string          `json:"kind"`
	Title      string          `json:"title"`
	Heavy      bool            `json:"heavy"`
	Status     string          `json:"status"`
	Params     json.RawMessage `json:"params,omitempty"`
	Error      string          `json:"error,omitempty"`
	CreatedAt  time.Time       `json:"createdAt"`
	StartedAt  *time.Time      `json:"startedAt,omitempty"`
	FinishedAt *time.Time      `json:"finishedAt,omitempty"`
}

type LogLine struct {
	ID   int64     `json:"id"`
	At   time.Time `json:"at"`
	Line string    `json:"line"`
}

const jobColumns = `id, kind, title, heavy, status, params, error, created_at, started_at, finished_at`

func scanJob(row interface{ Scan(...any) error }) (Job, error) {
	var j Job
	var params, errText sql.NullString
	var started, finished sql.NullTime
	if err := row.Scan(&j.ID, &j.Kind, &j.Title, &j.Heavy, &j.Status, &params, &errText, &j.CreatedAt, &started, &finished); err != nil {
		return Job{}, err
	}
	if params.Valid {
		j.Params = json.RawMessage(params.String)
	}
	j.Error = errText.String
	if started.Valid {
		j.StartedAt = &started.Time
	}
	if finished.Valid {
		j.FinishedAt = &finished.Time
	}
	return j, nil
}

func (s *Store) CreateJob(ctx context.Context, j Job) (int64, error) {
	var params any
	if len(j.Params) > 0 {
		params = string(j.Params)
	}
	res, err := s.db.ExecContext(ctx, `INSERT INTO jobs (kind, title, heavy, status, params, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		j.Kind, j.Title, j.Heavy, j.Status, params, utc(j.CreatedAt))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) GetJob(ctx context.Context, id int64) (Job, error) {
	j, err := scanJob(s.db.QueryRowContext(ctx, `SELECT `+jobColumns+` FROM jobs WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, ErrNotFound
	}
	return j, err
}

// ListJobs returns the newest jobs first.
func (s *Store) ListJobs(ctx context.Context, limit int) ([]Job, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+jobColumns+` FROM jobs ORDER BY id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Job{}
	for rows.Next() {
		j, err := scanJob(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

func (s *Store) MarkJobRunning(ctx context.Context, id int64, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE jobs SET status = 'running', started_at = ? WHERE id = ?`, utc(now), id)
	return err
}

// FinishJob records the final status; errText may be empty.
func (s *Store) FinishJob(ctx context.Context, id int64, status, errText string, now time.Time) error {
	var e any
	if errText != "" {
		e = errText
	}
	_, err := s.db.ExecContext(ctx, `UPDATE jobs SET status = ?, error = ?, finished_at = ? WHERE id = ?`, status, e, utc(now), id)
	return err
}

// InterruptUnfinished marks jobs left queued or running by a previous run of the program.
func (s *Store) InterruptUnfinished(ctx context.Context, now time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx, `UPDATE jobs SET status = 'interrupted', finished_at = ? WHERE status IN ('queued', 'running')`, utc(now))
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func (s *Store) AddLog(ctx context.Context, jobID int64, at time.Time, line string) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO job_logs (job_id, at, line) VALUES (?, ?, ?)`, jobID, utc(at), line)
	return err
}

// JobLogs returns up to limit lines of a job with id greater than after, oldest first.
func (s *Store) JobLogs(ctx context.Context, jobID, after int64, limit int) ([]LogLine, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, at, line FROM job_logs WHERE job_id = ? AND id > ? ORDER BY id LIMIT ?`, jobID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []LogLine{}
	for rows.Next() {
		var l LogLine
		if err := rows.Scan(&l.ID, &l.At, &l.Line); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// PruneJobs keeps the newest `keep` jobs (and their logs) and deletes the rest. Unfinished jobs are never deleted.
func (s *Store) PruneJobs(ctx context.Context, keep int) error {
	var cutoff sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT id FROM jobs ORDER BY id DESC LIMIT 1 OFFSET ?`, keep).Scan(&cutoff)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM job_logs WHERE job_id IN (SELECT id FROM jobs WHERE id <= ? AND status NOT IN ('queued', 'running'))`, cutoff.Int64); err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `DELETE FROM jobs WHERE id <= ? AND status NOT IN ('queued', 'running')`, cutoff.Int64)
	return err
}
