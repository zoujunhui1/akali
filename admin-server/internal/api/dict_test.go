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

// Stands in for dict_build.py: JSON for "status", otherwise echoes the command line.
const fakeDictPython = `#!/bin/sh
cmd=$2
shift 2
if [ "$cmd" = status ]; then echo '{"local":null,"server":{"size":5,"sha256":"x"},"server_error":null}'; exit 0; fi
echo "cmd=$cmd $*"
`

func newDictServer(t *testing.T) (*artEnv, string) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	dir := t.TempDir()
	py := filepath.Join(dir, "python")
	if err := os.WriteFile(py, []byte(fakeDictPython), 0o755); err != nil {
		t.Fatal(err)
	}
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	jm := jobstest.New()
	r, err := api.New(api.Config{Dict: &api.Dict{Python: py, ToolsDir: dir, Root: "/data/dict"}},
		svc, api.Jobs{Runner: jobs.New(context.Background(), jm), Store: jm})
	if err != nil {
		t.Fatal(err)
	}
	return &artEnv{r: r, jm: jm}, login(t, r).Value
}

func TestDictRoutesNeedLoginAndStatusPassesTheToolsJSON(t *testing.T) {
	e, tok := newDictServer(t)
	if w := do(e.r, "GET", "/api/dict", "", nil, false); w.Code != 401 {
		t.Fatalf("status without login = %d", w.Code)
	}
	if w := do(e.r, "POST", "/api/dict/build", `{}`, nil, true); w.Code != 401 {
		t.Fatalf("build without login = %d", w.Code)
	}
	w := do(e.r, "GET", "/api/dict", "", cookie(tok), false)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"sha256":"x"`) {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestDictJobsRunTheToolWithTheConfiguredPaths(t *testing.T) {
	e, tok := newDictServer(t)
	for _, tc := range []struct{ path, want string }{
		{"/api/dict/build", "cmd=build --out /data/dict/build/dict.db --source /data/dict/ecdict/ecdict.csv"},
		{"/api/dict/compare", "cmd=compare --out /data/dict/build/dict.db"},
		{"/api/dict/upload", "cmd=upload --out /data/dict/build/dict.db"},
	} {
		id := jobIDOf(t, do(e.r, "POST", tc.path, `{}`, cookie(tok), true))
		if st := waitJob(t, e, id); st != "succeeded" {
			t.Fatalf("%s: %v", tc.path, e.jm.Lines(id))
		}
		if got := e.jm.Lines(id)[0]; got != tc.want {
			t.Errorf("%s: got %q want %q", tc.path, got, tc.want)
		}
	}
}
