package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/akali"
	"github.com/zoujunhui1/akali/admin-server/internal/api"
	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/authtest"
	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/jobstest"
)

type fakeUpstream struct {
	configured bool
	deleted    []string
	fail       bool
}

func (f *fakeUpstream) Configured() bool { return f.configured }
func (f *fakeUpstream) Articles(context.Context) ([]akali.Article, error) {
	if f.fail {
		return nil, errors.New("down")
	}
	return []akali.Article{{Code: "R0001", Title: "Made Up", WordCount: 3}}, nil
}
func (f *fakeUpstream) DeleteArticle(_ context.Context, code string) error {
	f.deleted = append(f.deleted, code)
	return nil
}

// A stand-in for the Python tool: writes a one-article draft for previews, accepts uploads.
const fakePython = `#!/bin/sh
kind=$2
for a in "$@"; do case "$a" in --draft-out=*) out=${a#--draft-out=};; esac; done
if [ "$kind" = draft ]; then echo "uploading $3"; exit 0; fi
printf '{"source":"s","articles":[{"title":"Made Up","body":[["One two.","Three."],["Four."]]}]}' > "$out"
echo "kind=$kind"
`

type artEnv struct {
	r    *gin.Engine
	jm   *jobstest.Mem
	up   *fakeUpstream
	work string
}

func newArticleServer(t *testing.T) (*artEnv, *http.Cookie) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	dir := t.TempDir()
	py := filepath.Join(dir, "python")
	if err := os.WriteFile(py, []byte(fakePython), 0o755); err != nil {
		t.Fatal(err)
	}
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	jm := jobstest.New()
	up := &fakeUpstream{configured: true}
	work := filepath.Join(dir, "work")
	r, err := api.New(api.Config{Articles: &api.Articles{Upstream: up, Python: py, ToolsDir: dir, WorkDir: work}},
		svc, api.Jobs{Runner: jobs.New(context.Background(), jm), Store: jm})
	if err != nil {
		t.Fatal(err)
	}
	return &artEnv{r, jm, up, work}, login(t, r)
}

func waitJob(t *testing.T, e *artEnv, id int64) string {
	t.Helper()
	for i := 0; i < 200; i++ {
		switch st := e.jm.Status(id); st {
		case "succeeded", "failed", "cancelled":
			return st
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("job did not finish")
	return ""
}

func jobIDOf(t *testing.T, w *httptest.ResponseRecorder) int64 {
	t.Helper()
	var out struct{ ID int64 }
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &out) != nil || out.ID == 0 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	return out.ID
}

func idPath(prefix string, id int64) string {
	b, _ := json.Marshal(id)
	return prefix + string(b)
}

func TestArticleRoutesNeedLogin(t *testing.T) {
	e, _ := newArticleServer(t)
	if w := do(e.r, "GET", "/api/articles", "", nil, false); w.Code != 401 {
		t.Fatalf("list without login = %d", w.Code)
	}
	if w := do(e.r, "POST", "/api/articles/preview", `{"kind":"text","target":"x"}`, nil, true); w.Code != 401 {
		t.Fatalf("preview without login = %d", w.Code)
	}
}

func TestTextPreviewThenUpload(t *testing.T) {
	e, c := newArticleServer(t)
	id := jobIDOf(t, do(e.r, "POST", "/api/articles/preview", `{"kind":"text","target":"Hello there. Nice day."}`, c, true))
	if st := waitJob(t, e, id); st != "succeeded" {
		t.Fatalf("preview %s: %v", st, e.jm.Lines(id))
	}
	w := do(e.r, "GET", idPath("/api/articles/drafts/", id), "", c, false)
	var d struct {
		Source   string
		Articles []struct {
			Title                        string
			Paragraphs, Sentences, Words int
			First                        string
		}
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &d) != nil || len(d.Articles) != 1 {
		t.Fatalf("draft %d %s", w.Code, w.Body)
	}
	a := d.Articles[0]
	if a.Title != "Made Up" || a.Paragraphs != 2 || a.Sentences != 3 || a.Words != 4 || a.First != "One two." {
		t.Fatalf("summary %+v", a)
	}
	if entries, _ := os.ReadDir(e.work); len(entries) != 1 {
		t.Fatalf("work dir should hold only the draft, has %d entries", len(entries))
	}

	up := jobIDOf(t, do(e.r, "POST", "/api/articles/upload", `{"draft":`+jsonInt(id)+`,"title":"New title"}`, c, true))
	if st := waitJob(t, e, up); st != "succeeded" {
		t.Fatalf("upload %s: %v", st, e.jm.Lines(up))
	}
	if w := do(e.r, "GET", idPath("/api/articles/drafts/", id), "", c, false); w.Code != 404 {
		t.Fatalf("draft should be gone after upload, got %d", w.Code)
	}
}

func jsonInt(n int64) string { return strings.TrimSpace(idPath("", n)) }

func TestUrlPreviewRejectsOtherSchemes(t *testing.T) {
	e, c := newArticleServer(t)
	for _, bad := range []string{"file:///etc/passwd", "ftp://x.test/a", "--help", "javascript:1", "http://"} {
		body, _ := json.Marshal(map[string]string{"kind": "url", "target": bad})
		if w := do(e.r, "POST", "/api/articles/preview", string(body), c, true); w.Code != 400 {
			t.Errorf("%q = %d", bad, w.Code)
		}
	}
	if w := do(e.r, "POST", "/api/articles/preview", `{"kind":"url","target":"https://news.example.test/a"}`, c, true); w.Code != 200 {
		t.Errorf("good url = %d %s", w.Code, w.Body)
	}
}

func multipartReq(t *testing.T, name, content string, c *http.Cookie) *http.Request {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write([]byte(content))
	mw.WriteField("title", "My title")
	mw.Close()
	req := httptest.NewRequest("POST", "/api/articles/preview", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Requested-With", "akali-admin")
	req.AddCookie(c)
	return req
}

func TestFilePreview(t *testing.T) {
	e, c := newArticleServer(t)
	w := httptest.NewRecorder()
	e.r.ServeHTTP(w, multipartReq(t, "../../Book.EPUB", "data", c))
	id := jobIDOf(t, w)
	if st := waitJob(t, e, id); st != "succeeded" {
		t.Fatalf("%s: %v", st, e.jm.Lines(id))
	}
	if lines := e.jm.Lines(id); len(lines) == 0 || lines[0] != "kind=file" {
		t.Fatalf("log %v", lines)
	}

	w = httptest.NewRecorder()
	e.r.ServeHTTP(w, multipartReq(t, "run.sh", "data", c))
	if w.Code != 400 {
		t.Fatalf("a .sh file = %d", w.Code)
	}
}

func TestListAndDelete(t *testing.T) {
	e, c := newArticleServer(t)
	if w := do(e.r, "GET", "/api/articles", "", c, false); w.Code != 200 || !strings.Contains(w.Body.String(), "R0001") {
		t.Fatalf("list %d %s", w.Code, w.Body)
	}
	if w := do(e.r, "DELETE", "/api/articles/R0001", "", c, true); w.Code != 200 || len(e.up.deleted) != 1 || e.up.deleted[0] != "R0001" {
		t.Fatalf("delete %d %v", w.Code, e.up.deleted)
	}
	for _, bad := range []string{"x", "R1", "R0001%2F..%2Fa", "L0001"} {
		if w := do(e.r, "DELETE", "/api/articles/"+bad, "", c, true); w.Code != 400 && w.Code != 404 {
			t.Errorf("%q = %d", bad, w.Code)
		}
	}
	if len(e.up.deleted) != 1 {
		t.Fatalf("bad codes must not reach the server: %v", e.up.deleted)
	}
	e.up.fail = true
	if w := do(e.r, "GET", "/api/articles", "", c, false); w.Code != 502 {
		t.Fatalf("upstream down = %d", w.Code)
	}
	e.up.configured = false
	if w := do(e.r, "POST", "/api/articles/upload", `{"draft":1}`, c, true); w.Code != 503 {
		t.Fatalf("not configured = %d", w.Code)
	}
}

func TestUploadNeedsAnExistingDraft(t *testing.T) {
	e, c := newArticleServer(t)
	if w := do(e.r, "POST", "/api/articles/upload", `{"draft":999}`, c, true); w.Code != 404 {
		t.Fatalf("missing draft = %d", w.Code)
	}
}
