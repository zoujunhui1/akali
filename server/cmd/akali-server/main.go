package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/zoujunhui1/akali/server/internal/api"
	"github.com/zoujunhui1/akali/server/internal/store"
)

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("environment variable %s is required", name)
	}
	return v
}

func main() {
	st, err := store.Open(env("AKALI_DSN"))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer st.Close()

	handler, err := api.New(api.Config{
		AppToken:   env("AKALI_APP_TOKEN"),
		AdminToken: env("AKALI_ADMIN_TOKEN"),
		AudioDir:   env("AKALI_AUDIO_DIR"),
	}, st)
	if err != nil {
		log.Fatal(err)
	}

	addr := os.Getenv("AKALI_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	srv := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	log.Printf("akali-server listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}
