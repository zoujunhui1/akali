package store

import "context"

// Stats are plain counts for the admin status page. Deleted rows (tombstones) are not counted.
type Stats struct {
	Rev         int64
	Lessons     int
	VocabTopics int
	Articles    int
	Notebook    int // words in the notebook, not counting removed ones
	Favorites   int
	Mastered    int
}

func (s *Store) Stats(ctx context.Context) (Stats, error) {
	var st Stats
	one := func(dst any, q string) error { return s.db.QueryRowContext(ctx, q).Scan(dst) }
	for _, q := range []struct {
		dst any
		sql string
	}{
		{&st.Rev, `SELECT rev FROM meta WHERE id = 1`},
		{&st.Lessons, `SELECT COUNT(*) FROM lessons WHERE deleted = 0`},
		{&st.VocabTopics, `SELECT COUNT(*) FROM vocab_lessons WHERE deleted = 0`},
		{&st.Articles, `SELECT COUNT(*) FROM articles WHERE deleted = 0`},
		{&st.Notebook, `SELECT COUNT(*) FROM notebook WHERE state <> 'none'`},
		{&st.Favorites, `SELECT COUNT(*) FROM vocab_marks WHERE state = 'favorite'`},
		{&st.Mastered, `SELECT COUNT(*) FROM vocab_marks WHERE state = 'mastered'`},
	} {
		if err := one(q.dst, q.sql); err != nil {
			return Stats{}, err
		}
	}
	return st, nil
}
