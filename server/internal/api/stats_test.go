package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

type statsResp struct {
	Rev         int64 `json:"rev"`
	Lessons     int   `json:"lessons"`
	VocabTopics int   `json:"vocab_topics"`
	Articles    int   `json:"articles"`
	Notebook    int   `json:"notebook"`
	Favorites   int   `json:"favorites"`
	Mastered    int   `json:"mastered"`
	AI          struct {
		Configured bool `json:"configured"`
		UsedToday  int  `json:"used_today"`
		Limit      int  `json:"limit"`
	} `json:"ai"`
	AudioBytes int64 `json:"audio_bytes"`
	Dict       *struct {
		Size int64 `json:"size"`
	} `json:"dict"`
}

func getStats(t *testing.T, r interface {
	ServeHTTP(http.ResponseWriter, *http.Request)
}) statsResp {
	t.Helper()
	w := do(r, "GET", "/admin/stats", adminTok, nil)
	mustOK(t, w, 200)
	var s statsResp
	if err := json.Unmarshal(w.Body.Bytes(), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestStatsNeedTheAdminToken(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "GET", "/admin/stats", "", nil), 401)
	mustOK(t, do(r, "GET", "/admin/stats", appTok, nil), 401)
}

func TestStatsCountWhatIsThereAndSkipWhatWasDeleted(t *testing.T) {
	r, dir := newServer(t)
	empty := getStats(t, r)
	if empty.Lessons != 0 || empty.VocabTopics != 0 || empty.Articles != 0 || empty.AI.Configured || empty.AI.Limit != 1000 || empty.Dict != nil {
		t.Fatalf("empty server: %+v", empty)
	}

	mustOK(t, do(r, "PUT", "/admin/lessons/B2-001", adminTok, bytes.NewBufferString(`{"title":"One","transcript":"Hello."}`)), 204)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-002", adminTok, bytes.NewBufferString(`{"title":"Two","transcript":"Hi."}`)), 204)
	mustOK(t, do(r, "DELETE", "/admin/lessons/B2-002", adminTok, nil), 204)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-17", adminTok, bytes.NewBufferString(vocabDoc)), 204)
	mustOK(t, do(r, "PUT", "/admin/articles/R0001", adminTok, bytes.NewBufferString(`{"title":"A","source":"","body":[["One."]]}`)), 200)
	mustOK(t, do(r, "PUT", "/api/v1/marks", appTok, bytes.NewBufferString(
		`{"marks":[{"code":"V01-17","key":"31-1","state":"favorite"},{"code":"V01-17","key":"31-2","state":"mastered"},{"code":"V01-17","key":"31-3","state":"none"}]}`)), 200)
	if err := os.MkdirAll(filepath.Join(dir, "dict"), 0o755); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(dir, "dict", "dict.db"), []byte("12345"), 0o644)
	os.WriteFile(filepath.Join(dir, "B2-001.mp3"), []byte("abcdefgh"), 0o644)

	s := getStats(t, r)
	if s.Lessons != 1 || s.VocabTopics != 1 || s.Articles != 1 || s.Favorites != 1 || s.Mastered != 1 {
		t.Fatalf("counts: %+v", s)
	}
	if s.Rev == 0 || s.AudioBytes < 13 || s.Dict == nil || s.Dict.Size != 5 {
		t.Fatalf("rev/files: %+v dict=%+v", s, s.Dict)
	}
}

func TestStatsShowTodaysAIUse(t *testing.T) {
	var calls int32
	r := aiServer(t, &calls, func(_ string, w http.ResponseWriter) { reply(w, goodNote) }, 50)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-17", adminTok, bytes.NewBufferString(vocabDoc)), 204)
	mustOK(t, do(r, "POST", "/api/v1/vocab/V01-17/entries/31-1/explain", appTok, nil), 200)
	s := getStats(t, r)
	if !s.AI.Configured || s.AI.UsedToday != 1 || s.AI.Limit != 50 {
		t.Fatalf("ai: %+v", s.AI)
	}
}
