package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/store"
)

const (
	appTok   = "app-token"
	adminTok = "admin-token"
)

// newServer needs a MySQL database (AKALI_TEST_DSN); the test is skipped without one.
func newServer(t *testing.T) (*gin.Engine, string) {
	t.Helper()
	return newServerWith(t, func(*Config) {})
}

func newServerWith(t *testing.T, tweak func(*Config)) (*gin.Engine, string) {
	t.Helper()
	dsn := os.Getenv("AKALI_TEST_DSN")
	if dsn == "" {
		t.Skip("AKALI_TEST_DSN not set")
	}
	gin.SetMode(gin.TestMode)
	st, err := store.Open(dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if err := st.Reset(context.Background()); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	cfg := Config{AppToken: appTok, AdminToken: adminTok, AudioDir: dir}
	tweak(&cfg)
	r, err := New(cfg, st)
	if err != nil {
		t.Fatal(err)
	}
	return r, dir
}

func do(r http.Handler, method, path, token string, body io.Reader, hdr ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, body)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func mustOK(t *testing.T, w *httptest.ResponseRecorder, want int) {
	t.Helper()
	if w.Code != want {
		t.Fatalf("status %d, want %d: %s", w.Code, want, w.Body.String())
	}
}

type listResp struct {
	Rev     int64 `json:"rev"`
	Lessons []struct {
		Code       string          `json:"code"`
		Title      string          `json:"title"`
		Deleted    bool            `json:"deleted"`
		CreatedAt  int64           `json:"created_at"`
		Rev        int64           `json:"rev"`
		Transcript *string         `json:"transcript"`
		Timestamps json.RawMessage `json:"timestamps"`
		Vocab      json.RawMessage `json:"vocab"`
		Audio      *struct {
			Size int64 `json:"size"`
		} `json:"audio"`
	} `json:"lessons"`
}

func list(t *testing.T, r http.Handler, query string) listResp {
	t.Helper()
	w := do(r, "GET", "/api/v1/lessons"+query, appTok, nil)
	mustOK(t, w, 200)
	var out listResp
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

const lesson = `{"title":"Eulices Dream Job","source_url":"https://elllo.org/x.htm","transcript":"Hi, my name is Eulices."}`

func TestAuth(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "GET", "/api/v1/lessons", "", nil), 401)
	mustOK(t, do(r, "GET", "/api/v1/lessons", "wrong", nil), 401)
	mustOK(t, do(r, "GET", "/api/v1/lessons", adminTok, nil), 401) // admin token is not an app token
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", appTok, strings.NewReader(lesson)), 401)
	mustOK(t, do(r, "GET", "/healthz", "", nil), 200)
}

func TestLessonLifecycle(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(lesson)), 204)

	l := list(t, r, "")
	if len(l.Lessons) != 1 || l.Lessons[0].Code != "B2-036" || l.Lessons[0].Audio != nil || l.Lessons[0].Transcript != nil {
		t.Fatalf("unexpected list: %+v", l)
	}
	if l.Lessons[0].CreatedAt < 1_700_000_000_000 { // a real time in milliseconds, not zero or seconds
		t.Fatalf("created_at looks wrong: %d", l.Lessons[0].CreatedAt)
	}
	synced := l.Rev

	// nothing changed since
	if got := list(t, r, "?since="+itoa(synced)); len(got.Lessons) != 0 {
		t.Fatalf("expected no changes, got %+v", got)
	}

	ts := `{"version":1,"sentences":[{"paragraph":0,"text":"Hi.","start":0.5,"end":1.2}]}`
	vocab := `{"version":1,"items":[{"sentence":0,"text":"Hi","type":"word","en":"hi"}]}`
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/timestamps", adminTok, strings.NewReader(ts)), 204)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/vocab", adminTok, strings.NewReader(vocab)), 204)

	got := list(t, r, "?since="+itoa(synced)+"&content=1")
	if len(got.Lessons) != 1 || got.Lessons[0].Transcript == nil || *got.Lessons[0].Transcript != "Hi, my name is Eulices." {
		t.Fatalf("expected the lesson with content: %+v", got)
	}
	var parsed struct{ Sentences []struct{ Text string } }
	if err := json.Unmarshal(got.Lessons[0].Timestamps, &parsed); err != nil || parsed.Sentences[0].Text != "Hi." {
		t.Fatalf("timestamps not returned intact: %s", got.Lessons[0].Timestamps)
	}
	if len(got.Lessons[0].Vocab) == 0 {
		t.Fatal("vocab missing")
	}

	// detail
	w := do(r, "GET", "/api/v1/lessons/B2-036", appTok, nil)
	mustOK(t, w, 200)
	mustOK(t, do(r, "GET", "/api/v1/lessons/B2-099", appTok, nil), 404)
	mustOK(t, do(r, "GET", "/api/v1/lessons/nonsense", appTok, nil), 400)

	// delete leaves a tombstone for apps that synced earlier, but not for a fresh app
	before := list(t, r, "").Rev
	mustOK(t, do(r, "DELETE", "/admin/lessons/B2-036", adminTok, nil), 204)
	tomb := list(t, r, "?since="+itoa(before))
	if len(tomb.Lessons) != 1 || !tomb.Lessons[0].Deleted {
		t.Fatalf("expected a tombstone: %+v", tomb)
	}
	if fresh := list(t, r, ""); len(fresh.Lessons) != 0 {
		t.Fatalf("fresh sync should not see deleted lessons: %+v", fresh)
	}
	mustOK(t, do(r, "GET", "/api/v1/lessons/B2-036", appTok, nil), 404)
	mustOK(t, do(r, "DELETE", "/admin/lessons/B2-036", adminTok, nil), 404)
}

func TestAudioUploadAndRange(t *testing.T) {
	r, dir := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/audio", adminTok, strings.NewReader("x")), 404) // lesson must exist first
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(lesson)), 204)
	mustOK(t, do(r, "GET", "/api/v1/lessons/B2-036/audio", appTok, nil), 404) // no audio yet
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/audio", adminTok, bytes.NewReader(nil)), 400)

	data := []byte("0123456789abcdefghij")
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/audio", adminTok, bytes.NewReader(data)), 204)

	if l := list(t, r, ""); l.Lessons[0].Audio == nil || l.Lessons[0].Audio.Size != int64(len(data)) {
		t.Fatalf("audio info missing: %+v", l)
	}
	w := do(r, "GET", "/api/v1/lessons/B2-036/audio", appTok, nil)
	mustOK(t, w, 200)
	if !bytes.Equal(w.Body.Bytes(), data) || w.Header().Get("Content-Type") != "audio/mpeg" {
		t.Fatalf("wrong body or type: %q %s", w.Body.String(), w.Header().Get("Content-Type"))
	}
	w = do(r, "GET", "/api/v1/lessons/B2-036/audio", appTok, nil, "Range", "bytes=5-9")
	mustOK(t, w, 206)
	if w.Body.String() != "56789" {
		t.Fatalf("range body %q", w.Body.String())
	}

	mustOK(t, do(r, "DELETE", "/admin/lessons/B2-036", adminTok, nil), 204)
	if _, err := os.Stat(dir + "/B2-036.mp3"); !os.IsNotExist(err) {
		t.Fatal("audio file should be removed with the lesson")
	}
}

func TestRejectsBadInput(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(`{"transcript":"x"}`)), 400) // no title
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(lesson)), 204)
	for _, bad := range []string{`nope`, `{"sentences":[]}`, `{"sentences":[{"text":"a","start":2,"end":1}]}`} {
		mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/timestamps", adminTok, strings.NewReader(bad)), 400)
	}
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/vocab", adminTok, strings.NewReader(`{"version":1}`)), 400)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/vocab", adminTok, strings.NewReader(`{"items":[]}`)), 204)
	mustOK(t, do(r, "GET", "/api/v1/lessons?since=abc", appTok, nil), 400)
}

func TestReupsertKeepsDataAndRevivesDeleted(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(lesson)), 204)
	ts := `{"sentences":[{"text":"Hi.","start":0,"end":1}]}`
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036/timestamps", adminTok, strings.NewReader(ts)), 204)
	// updating the title must not wipe the timestamps
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok,
		strings.NewReader(`{"title":"New title","transcript":"Hi, my name is Eulices."}`)), 204)
	l := list(t, r, "?content=1")
	if l.Lessons[0].Title != "New title" || len(l.Lessons[0].Timestamps) == 0 {
		t.Fatalf("title not updated or timestamps lost: %+v", l.Lessons[0])
	}
	mustOK(t, do(r, "DELETE", "/admin/lessons/B2-036", adminTok, nil), 204)
	mustOK(t, do(r, "PUT", "/admin/lessons/B2-036", adminTok, strings.NewReader(lesson)), 204)
	if l := list(t, r, ""); len(l.Lessons) != 1 || l.Lessons[0].Deleted {
		t.Fatalf("deleted lesson should come back when re-added: %+v", l)
	}
}

func itoa(n int64) string { b, _ := json.Marshal(n); return string(b) }
