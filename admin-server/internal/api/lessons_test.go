package api_test

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/api"
	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/authtest"
	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/jobstest"
)

// Stands in for lessons_cli.py: prints JSON for "list", otherwise echoes the command and its arguments.
const fakeLessonsPython = `#!/bin/sh
cmd=$4
shift 4
if [ "$cmd" = list ]; then echo '{"lessons":[{"code":"B2-001"}],"server_error":null}'; exit 0; fi
if [ "$cmd" = bad ]; then exit 3; fi
echo "cmd=$cmd args=$*"
`

func newLessonServer(t *testing.T) (*artEnv, string) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	dir := t.TempDir()
	py := filepath.Join(dir, "python")
	if err := os.WriteFile(py, []byte(fakeLessonsPython), 0o755); err != nil {
		t.Fatal(err)
	}
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	jm := jobstest.New()
	r, err := api.New(api.Config{Lessons: &api.Lessons{Python: py, ToolsDir: dir, Library: "/lib"}},
		svc, api.Jobs{Runner: jobs.New(context.Background(), jm), Store: jm})
	if err != nil {
		t.Fatal(err)
	}
	e := &artEnv{r: r, jm: jm}
	return e, login(t, r).Value
}

func TestLessonRoutesNeedLogin(t *testing.T) {
	e, _ := newLessonServer(t)
	if w := do(e.r, "GET", "/api/lessons", "", nil, false); w.Code != 401 {
		t.Fatalf("list without login = %d", w.Code)
	}
}

func TestListReturnsTheToolsJSON(t *testing.T) {
	e, tok := newLessonServer(t)
	w := do(e.r, "GET", "/api/lessons", "", cookie(tok), false)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"B2-001"`) {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestProcessIsAHeavyJobAndOnlyTakesElllo(t *testing.T) {
	e, tok := newLessonServer(t)
	c := cookie(tok)
	for _, bad := range []string{"https://example.test/video/B2/B2-001-x.htm", "file:///etc/passwd", "--help", "https://elllo.org.evil.test/a.htm", ""} {
		body := `{"url":"` + bad + `"}`
		if w := do(e.r, "POST", "/api/lessons/process", body, c, true); w.Code != 400 {
			t.Errorf("%q = %d", bad, w.Code)
		}
	}
	id := jobIDOf(t, do(e.r, "POST", "/api/lessons/process", `{"url":"https://elllo.org/video/B2/B2-001-Made-Up.htm"}`, c, true))
	if st := waitJob(t, e, id); st != "succeeded" {
		t.Fatalf("%s %v", st, e.jm.Lines(id))
	}
	if lines := e.jm.Lines(id); len(lines) != 1 || lines[0] != "cmd=process args=https://elllo.org/video/B2/B2-001-Made-Up.htm" {
		t.Fatalf("log %v", lines)
	}
	if !e.jm.Heavy(id) {
		t.Fatal("processing a lesson must be a heavy job")
	}
}

func TestPerLessonActions(t *testing.T) {
	e, tok := newLessonServer(t)
	c := cookie(tok)
	for _, tc := range []struct {
		method, path, want string
		heavy              bool
	}{
		{"POST", "/api/lessons/B2-001/redo", "cmd=redo args=B2-001", true},
		{"POST", "/api/lessons/C1-044/upload", "cmd=upload args=C1-044", false},
		{"DELETE", "/api/lessons/B2-002", "cmd=delete args=B2-002", false},
		{"POST", "/api/lessons/vocab", "cmd=vocab args=", false},
	} {
		id := jobIDOf(t, do(e.r, tc.method, tc.path, `{}`, c, true))
		if st := waitJob(t, e, id); st != "succeeded" {
			t.Fatalf("%s %s: %v", tc.method, tc.path, e.jm.Lines(id))
		}
		if got := e.jm.Lines(id)[0]; got != tc.want {
			t.Errorf("%s: got %q want %q", tc.path, got, tc.want)
		}
		if e.jm.Heavy(id) != tc.heavy {
			t.Errorf("%s heavy = %v", tc.path, e.jm.Heavy(id))
		}
	}
	id := jobIDOf(t, do(e.r, "POST", "/api/lessons/vocab", `{"codes":["B2-001","C1-002"]}`, c, true))
	waitJob(t, e, id)
	if got := e.jm.Lines(id)[0]; got != "cmd=vocab args=B2-001 C1-002" {
		t.Errorf("vocab codes: %q", got)
	}
}

func TestBadLessonCodesNeverReachTheTool(t *testing.T) {
	e, tok := newLessonServer(t)
	c := cookie(tok)
	for _, p := range []string{"/api/lessons/--help/redo", "/api/lessons/B2-1/upload", "/api/lessons/D1-001/redo", "/api/lessons/B2-001x/upload"} {
		if w := do(e.r, "POST", p, `{}`, c, true); w.Code != 400 {
			t.Errorf("%s = %d", p, w.Code)
		}
	}
	if w := do(e.r, "POST", "/api/lessons/vocab", `{"codes":["--all"]}`, c, true); w.Code != 400 {
		t.Errorf("vocab with a flag = %d", w.Code)
	}
	if w := do(e.r, "DELETE", "/api/lessons/x", "", c, true); w.Code != 400 {
		t.Errorf("delete bad code = %d", w.Code)
	}
}

func cookie(value string) *http.Cookie {
	return &http.Cookie{Name: "akali_admin_session", Value: value}
}
