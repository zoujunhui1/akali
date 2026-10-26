// akali-admin is the admin system's backend.
//
//	akali-admin                 run the server
//	akali-admin set-password U  read a password from stdin and store it for user U
package main

import (
	"bufio"
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/zoujunhui1/akali/admin-server/internal/akali"
	"github.com/zoujunhui1/akali/admin-server/internal/api"
	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/jobs"
	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("environment variable %s is required", name)
	}
	return v
}

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func main() {
	st, err := store.Open(env("ADMIN_DSN"))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer st.Close()
	a := auth.New(st)

	if len(os.Args) == 3 && os.Args[1] == "set-password" {
		// the password arrives on stdin, so it never shows up in a command line or process list
		line, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil && line == "" {
			log.Fatal("no password on stdin")
		}
		if err := a.SetPassword(context.Background(), os.Args[2], strings.TrimRight(line, "\r\n")); err != nil {
			log.Fatal(err)
		}
		log.Printf("password stored for %s", os.Args[2])
		return
	}

	// on shutdown (systemd stop) running jobs are stopped and recorded as interrupted
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	runner := jobs.New(ctx, st)
	if err := runner.Recover(context.Background()); err != nil {
		log.Fatalf("jobs: %v", err)
	}

	// the phone-facing server's address and tokens; the Python tools read the same variables from our environment
	upstream := &akali.Client{Server: os.Getenv("AKALI_SERVER"), AdminToken: os.Getenv("AKALI_ADMIN_TOKEN"), AppToken: os.Getenv("AKALI_APP_TOKEN")}
	if !upstream.Configured() {
		log.Print("AKALI_SERVER, AKALI_ADMIN_TOKEN and AKALI_APP_TOKEN are not all set: uploading is switched off")
	}
	toolsDir := envOr("ADMIN_TOOLS_DIR", "/opt/akali-tools")
	cfg := api.Config{
		InsecureCookie: os.Getenv("ADMIN_INSECURE_COOKIE") == "1",
		Articles: &api.Articles{
			Upstream: upstream,
			Python:   envOr("ADMIN_PYTHON", toolsDir+"/venv/bin/python"),
			ToolsDir: toolsDir,
			WorkDir:  envOr("ADMIN_WORK_DIR", "/var/lib/akali-admin/work"),
		},
		Dict: &api.Dict{
			Python:   envOr("ADMIN_PYTHON", toolsDir+"/venv/bin/python"),
			ToolsDir: toolsDir,
			Root:     envOr("ADMIN_DICT_DIR", "/var/lib/akali-admin/dict"),
		},
		Lessons: &api.Lessons{
			Python:   envOr("ADMIN_PYTHON", toolsDir+"/venv/bin/python"),
			ToolsDir: toolsDir,
			Library:  envOr("ADMIN_ELLLO_DIR", "/var/lib/akali-admin/elllo"),
		},
	}
	handler, err := api.New(cfg, a, api.Jobs{Runner: runner, Store: st})
	if err != nil {
		log.Fatal(err)
	}
	addr := os.Getenv("ADMIN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:9090"
	}
	srv := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(shutdown)
	}()
	log.Printf("akali-admin listening on %s", addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	runner.Wait()
}
