package api

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

type marksResp struct {
	Rev   int64 `json:"rev"`
	Marks []struct {
		Code  string `json:"code"`
		Key   string `json:"key"`
		State string `json:"state"`
	} `json:"marks"`
}

func TestMarksRoundTripAndRemoval(t *testing.T) {
	r, _ := newServer(t)
	put := func(body string, want int) int64 {
		w := do(r, "PUT", "/api/v1/marks", appTok, bytes.NewBufferString(body))
		mustOK(t, w, want)
		var out struct{ Rev int64 }
		json.Unmarshal(w.Body.Bytes(), &out)
		return out.Rev
	}
	get := func(since string) marksResp {
		var m marksResp
		w := do(r, "GET", "/api/v1/marks?since="+since, appTok, nil)
		mustOK(t, w, 200)
		json.Unmarshal(w.Body.Bytes(), &m)
		return m
	}

	rev1 := put(`{"marks":[{"code":"V01-17","key":"31-1","state":"favorite"},{"code":"V01-17","key":"31-2","state":"mastered"}]}`, 200)
	m := get("0")
	if len(m.Marks) != 2 || m.Marks[0].State != "favorite" || m.Marks[1].State != "mastered" || m.Rev != rev1 {
		t.Fatalf("unexpected marks: %+v", m)
	}

	// changing a mark shows up in "since", removing it leaves a tombstone, and a fresh sync no longer sees it
	rev2 := put(`{"marks":[{"code":"V01-17","key":"31-1","state":"mastered"}]}`, 200)
	if rev2 <= rev1 {
		t.Fatal("revision must grow")
	}
	if m := get(itoa(rev1)); len(m.Marks) != 1 || m.Marks[0].Key != "31-1" || m.Marks[0].State != "mastered" {
		t.Fatalf("expected only the changed mark: %+v", m)
	}
	rev3 := put(`{"marks":[{"code":"V01-17","key":"31-2","state":"none"}]}`, 200)
	if m := get(itoa(rev2)); len(m.Marks) != 1 || m.Marks[0].State != "none" {
		t.Fatalf("expected a removal: %+v", m)
	}
	if m := get("0"); len(m.Marks) != 1 || m.Marks[0].Key != "31-1" || m.Rev != rev3 {
		t.Fatalf("a fresh sync should only see live marks: %+v", m)
	}

	// the last one in a batch wins
	put(`{"marks":[{"code":"V01-17","key":"31-3","state":"favorite"},{"code":"V01-17","key":"31-3","state":"mastered"}]}`, 200)
	for _, mk := range get("0").Marks {
		if mk.Key == "31-3" && mk.State != "mastered" {
			t.Fatalf("last mark should win: %+v", mk)
		}
	}
}

func TestMarksRejectBadInput(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "GET", "/api/v1/marks", "", nil), 401)
	mustOK(t, do(r, "GET", "/api/v1/marks", adminTok, nil), 401)
	mustOK(t, do(r, "PUT", "/api/v1/marks", adminTok, bytes.NewBufferString(`{"marks":[]}`)), 401)
	for _, bad := range []string{
		`{"marks":[]}`,
		`nope`,
		`{"marks":[{"code":"B2-001","key":"31-1","state":"favorite"}]}`,     // not a vocabulary code
		`{"marks":[{"code":"V01-17","key":"3'; DROP","state":"favorite"}]}`, // bad key
		`{"marks":[{"code":"V01-17","key":"31-1","state":"loved"}]}`,        // unknown state
		`{"marks":[{"code":"V01-17","key":"31-1","state":"favorite"},{"code":"V01-17","key":"","state":"none"}]}`,
		`{"marks":[` + strings.Repeat(`{"code":"V01-17","key":"31-1","state":"none"},`, 600) + `{"code":"V01-17","key":"31-1","state":"none"}]}`,
	} {
		mustOK(t, do(r, "PUT", "/api/v1/marks", appTok, bytes.NewBufferString(bad)), 400)
	}
	mustOK(t, do(r, "GET", "/api/v1/marks?since=-1", appTok, nil), 400)
	if m := func() marksResp {
		var m marksResp
		json.Unmarshal(do(r, "GET", "/api/v1/marks", appTok, nil).Body.Bytes(), &m)
		return m
	}(); len(m.Marks) != 0 {
		t.Fatalf("a rejected request must not save anything: %+v", m)
	}
}
