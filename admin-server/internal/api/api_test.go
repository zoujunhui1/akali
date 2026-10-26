package api_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/api"
	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/authtest"
	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/jobstest"
)

const pw = "correct horse battery"

func newServer(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	svc := auth.NewWithCost(authtest.New(), 4)
	if err := svc.SetPassword(context.Background(), "owner", pw); err != nil {
		t.Fatal(err)
	}
	jm := jobstest.New()
	r, err := api.New(api.Config{}, svc, api.Jobs{Runner: jobs.New(context.Background(), jm), Store: jm, SelfCheckStep: 5 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func do(r http.Handler, method, path, body string, cookie *http.Cookie, header bool) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if header {
		req.Header.Set("X-Requested-With", "akali-admin")
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func login(t *testing.T, r http.Handler) *http.Cookie {
	t.Helper()
	w := do(r, "POST", "/api/auth/login", `{"username":"owner","password":"`+pw+`"}`, nil, true)
	if w.Code != 200 {
		t.Fatalf("login %d %s", w.Code, w.Body)
	}
	for _, c := range w.Result().Cookies() {
		if c.Name == "akali_admin_session" {
			return c
		}
	}
	t.Fatal("no session cookie")
	return nil
}

func TestHealthzNeedsNoLogin(t *testing.T) {
	if w := do(newServer(t), "GET", "/healthz", "", nil, false); w.Code != 200 {
		t.Fatalf("%d", w.Code)
	}
}

func TestMeNeedsALogin(t *testing.T) {
	r := newServer(t)
	if w := do(r, "GET", "/api/auth/me", "", nil, false); w.Code != 401 {
		t.Fatalf("anonymous /me = %d", w.Code)
	}
	c := login(t, r)
	if w := do(r, "GET", "/api/auth/me", "", c, false); w.Code != 200 || !strings.Contains(w.Body.String(), "owner") {
		t.Fatalf("/me = %d %s", w.Code, w.Body)
	}
}

func TestSessionCookieIsHardened(t *testing.T) {
	c := login(t, newServer(t))
	if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode {
		t.Fatalf("cookie flags: httponly=%v secure=%v samesite=%v", c.HttpOnly, c.Secure, c.SameSite)
	}
}

func TestLogoutEndsTheSession(t *testing.T) {
	r := newServer(t)
	c := login(t, r)
	if w := do(r, "POST", "/api/auth/logout", "", c, true); w.Code != 200 {
		t.Fatalf("logout %d", w.Code)
	}
	if w := do(r, "GET", "/api/auth/me", "", c, false); w.Code != 401 {
		t.Fatalf("old cookie still works: %d", w.Code)
	}
}

func TestWritesWithoutOurHeaderAreRefused(t *testing.T) {
	w := do(newServer(t), "POST", "/api/auth/login", `{"username":"owner","password":"`+pw+`"}`, nil, false)
	if w.Code != 403 {
		t.Fatalf("cross-site style login = %d", w.Code)
	}
}

func TestWrongPasswordIs401AndRepeatedFailuresAre429(t *testing.T) {
	r := newServer(t)
	bad := `{"username":"owner","password":"wrong wrong wrong"}`
	if w := do(r, "POST", "/api/auth/login", bad, nil, true); w.Code != 401 {
		t.Fatalf("wrong password = %d", w.Code)
	}
	for i := 0; i < 5; i++ {
		do(r, "POST", "/api/auth/login", bad, nil, true)
	}
	if w := do(r, "POST", "/api/auth/login", `{"username":"owner","password":"`+pw+`"}`, nil, true); w.Code != 429 {
		t.Fatalf("after 6 failures = %d", w.Code)
	}
}

func TestEmptyLoginIs400(t *testing.T) {
	if w := do(newServer(t), "POST", "/api/auth/login", `{}`, nil, true); w.Code != 400 {
		t.Fatalf("%d", w.Code)
	}
}

func TestJobEndpointsNeedALogin(t *testing.T) {
	r := newServer(t)
	for _, c := range [][2]string{{"GET", "/api/jobs"}, {"GET", "/api/jobs/1"}, {"POST", "/api/jobs/1/cancel"}, {"POST", "/api/jobs/selfcheck"}} {
		if w := do(r, c[0], c[1], "", nil, true); w.Code != 401 {
			t.Fatalf("%s %s without a login = %d", c[0], c[1], w.Code)
		}
	}
}

func TestSelfCheckJobRunsAndLogsAndLists(t *testing.T) {
	r := newServer(t)
	c := login(t, r)
	w := do(r, "POST", "/api/jobs/selfcheck", `{"heavy":false}`, c, true)
	if w.Code != 200 {
		t.Fatalf("start %d %s", w.Code, w.Body)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		w = do(r, "GET", "/api/jobs/1", "", c, false)
		if strings.Contains(w.Body.String(), `"status":"succeeded"`) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("never finished: %s", w.Body)
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !strings.Contains(w.Body.String(), "step 6 of 6") || !strings.Contains(w.Body.String(), `"done"`) {
		t.Fatalf("log missing: %s", w.Body)
	}
	if w = do(r, "GET", "/api/jobs/1?after=7", "", c, false); strings.Contains(w.Body.String(), "step 1 of 6") || !strings.Contains(w.Body.String(), `"logs":[]`) {
		t.Fatalf("after=7 should return no lines: %s", w.Body)
	}
	if w = do(r, "GET", "/api/jobs", "", c, false); !strings.Contains(w.Body.String(), "自检任务") {
		t.Fatalf("list: %s", w.Body)
	}
}

func TestCancelAndErrorsForJobs(t *testing.T) {
	r := newServer(t)
	c := login(t, r)
	if w := do(r, "GET", "/api/jobs/abc", "", c, false); w.Code != 400 {
		t.Fatalf("bad id = %d", w.Code)
	}
	if w := do(r, "GET", "/api/jobs/999", "", c, false); w.Code != 404 {
		t.Fatalf("missing job = %d", w.Code)
	}
	if w := do(r, "POST", "/api/jobs/999/cancel", "", c, true); w.Code != 404 {
		t.Fatalf("cancel missing = %d", w.Code)
	}
	do(r, "POST", "/api/jobs/selfcheck", `{}`, c, true)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if w := do(r, "GET", "/api/jobs/1", "", c, false); strings.Contains(w.Body.String(), `"status":"succeeded"`) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if w := do(r, "POST", "/api/jobs/1/cancel", "", c, true); w.Code != 409 {
		t.Fatalf("cancel finished = %d", w.Code)
	}
}
