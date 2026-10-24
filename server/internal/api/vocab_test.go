package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"testing"
)

func zipOf(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for name, body := range files {
		f, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		f.Write([]byte(body))
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

const vocabDoc = `{"title":"Colors","pages":[31],"entries":[{"label":"1","text":"red","page":31,"audio":"audio/31-1.mp3"}]}`

type vocabList struct {
	Rev     int64 `json:"rev"`
	Lessons []struct {
		Code    string `json:"code"`
		Unit    int    `json:"unit"`
		Number  int    `json:"number"`
		Title   string `json:"title"`
		Deleted bool   `json:"deleted"`
		Bundle  *struct {
			Size   int64  `json:"size"`
			SHA256 string `json:"sha256"`
		} `json:"bundle"`
		Entries []struct {
			Text string `json:"text"`
		} `json:"entries"`
	} `json:"lessons"`
}

func TestVocabRoundTrip(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-17", adminTok, bytes.NewBufferString(vocabDoc)), 204)

	// the topic exists but has no files yet
	var l vocabList
	w := do(r, "GET", "/api/v1/vocab?since=0", appTok, nil)
	mustOK(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &l)
	if len(l.Lessons) != 1 || l.Lessons[0].Code != "V01-17" || l.Lessons[0].Unit != 1 || l.Lessons[0].Number != 17 ||
		l.Lessons[0].Bundle != nil || l.Lessons[0].Entries[0].Text != "red" {
		t.Fatalf("unexpected list: %s", w.Body.String())
	}
	mustOK(t, do(r, "GET", "/api/v1/vocab/V01-17/bundle", appTok, nil), 404)

	// upload and download the bundle byte for byte
	body := zipOf(t, map[string]string{"page-31.jpg": "img", "audio/31-1.mp3": "snd"})
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-17/bundle", adminTok, bytes.NewReader(body)), 204)
	w = do(r, "GET", "/api/v1/vocab/V01-17/bundle", appTok, nil)
	mustOK(t, w, 200)
	if !bytes.Equal(w.Body.Bytes(), body) {
		t.Fatal("bundle differs from what was uploaded")
	}
	mustOK(t, do(r, "GET", "/api/v1/vocab/V01-17/bundle", appTok, nil, "Range", "bytes=0-9"), 206)

	// changes since the first listing show the bundle; then a delete leaves a tombstone
	json.Unmarshal(do(r, "GET", "/api/v1/vocab?since=0", appTok, nil).Body.Bytes(), &l)
	if l.Lessons[0].Bundle == nil || l.Lessons[0].Bundle.Size != int64(len(body)) {
		t.Fatalf("bundle info missing: %+v", l.Lessons[0])
	}
	before := l.Rev
	mustOK(t, do(r, "DELETE", "/admin/vocab/V01-17", adminTok, nil), 204)
	var after vocabList
	json.Unmarshal(do(r, "GET", "/api/v1/vocab?since="+itoa(before), appTok, nil).Body.Bytes(), &after)
	if len(after.Lessons) != 1 || !after.Lessons[0].Deleted {
		t.Fatalf("expected a tombstone: %+v", after)
	}
	mustOK(t, do(r, "GET", "/api/v1/vocab/V01-17/bundle", appTok, nil), 404)
}

func TestVocabRejectsBadInput(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "GET", "/api/v1/vocab", "", nil), 401)
	mustOK(t, do(r, "GET", "/api/v1/vocab", adminTok, nil), 401)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01", appTok, bytes.NewBufferString(vocabDoc)), 401)
	mustOK(t, do(r, "PUT", "/admin/vocab/B2-001", adminTok, bytes.NewBufferString(vocabDoc)), 400) // not a vocabulary code
	mustOK(t, do(r, "PUT", "/admin/vocab/V00-001", adminTok, bytes.NewBufferString(vocabDoc)), 400)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01", adminTok, bytes.NewBufferString(`{"title":"","pages":[1],"entries":[]}`)), 400)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01", adminTok, bytes.NewBufferString(`{"title":"x","pages":[],"entries":[]}`)), 400)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01", adminTok,
		bytes.NewBufferString(`{"title":"x","pages":[1],"entries":[{"label":"1","text":"a","page":1,"audio":"../x.mp3"}]}`)), 400)

	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01", adminTok, bytes.NewBufferString(vocabDoc)), 204)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01/bundle", adminTok, bytes.NewBufferString("not a zip")), 400)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-01/bundle", adminTok, bytes.NewReader(zipOf(t, map[string]string{"../evil": "x"}))), 400)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-09/bundle", adminTok, bytes.NewReader(zipOf(t, map[string]string{"a": "x"}))), 404)
}

func TestClearVocabNotes(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-17", adminTok, bytes.NewBufferString(vocabDoc)), 204)
	mustOK(t, do(r, "PUT", "/admin/vocab/V01-18", adminTok, bytes.NewBufferString(vocabDoc)), 204)
	var l struct {
		Rev int64 `json:"rev"`
	}
	json.Unmarshal(do(r, "GET", "/api/v1/vocab?since=0", appTok, nil).Body.Bytes(), &l)

	// nothing to clear yet: nothing changes for the phones
	w := do(r, "DELETE", "/admin/vocab-notes", adminTok, nil)
	mustOK(t, w, 200)
	var out struct {
		Cleared int `json:"cleared"`
	}
	json.Unmarshal(w.Body.Bytes(), &out)
	if out.Cleared != 0 {
		t.Fatalf("cleared %d, want 0", out.Cleared)
	}
	mustOK(t, do(r, "DELETE", "/admin/vocab-notes", "wrong", nil), 401)
}
