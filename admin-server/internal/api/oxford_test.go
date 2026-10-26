package api_test

import (
	"context"
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

// Stands in for oxford_cli.py: JSON for "list", otherwise echoes the command and its topic codes.
const fakeOxfordPython = `#!/bin/sh
shift 3
cmd=$1
shift
if [ "$cmd" = list ]; then echo '{"topics":[{"code":"V01-01"}],"server_error":null}'; exit 0; fi
echo "cmd=$cmd codes=$*"
`

func newOxfordServer(t *testing.T) (*artEnv, string) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	dir := t.TempDir()
	py := filepath.Join(dir, "python")
	if err := os.WriteFile(py, []byte(fakeOxfordPython), 0o755); err != nil {
		t.Fatal(err)
	}
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	jm := jobstest.New()
	r, err := api.New(api.Config{Oxford: &api.Oxford{Python: py, ToolsDir: dir, Root: "/data/book"}},
		svc, api.Jobs{Runner: jobs.New(context.Background(), jm), Store: jm})
	if err != nil {
		t.Fatal(err)
	}
	return &artEnv{r: r, jm: jm}, login(t, r).Value
}

func TestOxfordNeedsLoginAndListPassesTheToolsJSON(t *testing.T) {
	e, tok := newOxfordServer(t)
	if w := do(e.r, "GET", "/api/oxford", "", nil, false); w.Code != 401 {
		t.Fatalf("list without login = %d", w.Code)
	}
	w := do(e.r, "GET", "/api/oxford", "", cookie(tok), false)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "V01-01") {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestOxfordFixIsHeavyAndUploadIsLight(t *testing.T) {
	e, tok := newOxfordServer(t)
	for _, tc := range []struct {
		path, body, want string
		heavy            bool
	}{
		{"/api/oxford/fix", `{"codes":["V01-17","V02-03"]}`, "cmd=fix codes=V01-17 V02-03", true},
		{"/api/oxford/upload", `{}`, "cmd=upload codes=", false},
		{"/api/oxford/upload", `{"codes":["V12-05"]}`, "cmd=upload codes=V12-05", false},
	} {
		id := jobIDOf(t, do(e.r, "POST", tc.path, tc.body, cookie(tok), true))
		if st := waitJob(t, e, id); st != "succeeded" {
			t.Fatalf("%s: %v", tc.path, e.jm.Lines(id))
		}
		if got := e.jm.Lines(id)[0]; got != tc.want {
			t.Errorf("got %q want %q", got, tc.want)
		}
		if e.jm.Heavy(id) != tc.heavy {
			t.Errorf("%s heavy = %v", tc.path, e.jm.Heavy(id))
		}
	}
}

func TestOxfordBadCodesNeverReachTheTool(t *testing.T) {
	e, tok := newOxfordServer(t)
	for _, body := range []string{`{"codes":["--all"]}`, `{"codes":["V1-1"]}`, `{"codes":["V01-17","x"]}`, `{"codes":["B2-001"]}`} {
		if w := do(e.r, "POST", "/api/oxford/fix", body, cookie(tok), true); w.Code != 400 {
			t.Errorf("%s = %d", body, w.Code)
		}
	}
}
