package akali

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestArticlesSkipsDeletedAndUsesAppToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer app-t" || r.URL.Path != "/api/v1/articles" {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		w.Write([]byte(`{"rev":3,"articles":[{"code":"R0001","title":"A","source":"s","word_count":5,"deleted":false},{"code":"R0002","title":"B","deleted":true}]}`))
	}))
	defer srv.Close()
	c := &Client{Server: srv.URL, AdminToken: "adm-t", AppToken: "app-t"}
	list, err := c.Articles(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].Code != "R0001" || list[0].WordCount != 5 {
		t.Fatalf("got %+v", list)
	}
}

func TestDeleteArticleUsesAdminToken(t *testing.T) {
	var gotAuth, gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotMethod, gotPath = r.Header.Get("Authorization"), r.Method, r.URL.Path
		w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	c := &Client{Server: srv.URL + "/", AdminToken: "adm-t", AppToken: "app-t"}
	if err := c.DeleteArticle(context.Background(), "R0001"); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer adm-t" || gotMethod != "DELETE" || gotPath != "/admin/articles/R0001" {
		t.Fatalf("got %s %s %s", gotMethod, gotPath, gotAuth)
	}
}

func TestErrorStatusIsReported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "x", 500) }))
	defer srv.Close()
	c := &Client{Server: srv.URL, AdminToken: "a", AppToken: "b"}
	if _, err := c.Articles(context.Background()); err == nil {
		t.Fatal("want an error")
	}
}

func TestStatsUsesAdminTokenAndReturnsTheServersJSON(t *testing.T) {
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		if r.URL.Path != "/admin/stats" {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(`{"lessons":71}`))
	}))
	defer srv.Close()
	c := &Client{Server: srv.URL, AdminToken: "adm-t", AppToken: "app-t"}
	data, err := c.Stats(context.Background())
	if err != nil || string(data) != `{"lessons":71}` || gotAuth != "Bearer adm-t" {
		t.Fatalf("%s %v %s", data, err, gotAuth)
	}
}
