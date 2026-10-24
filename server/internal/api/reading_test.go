package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/zoujunhui1/akali/server/internal/llm"
)

const articleDoc = `{"title":"A Short Story","source":"made up","body":[["It was a dark night.","The wind blew."],["Nobody was home."]]}`

type articlesResp struct {
	Rev      int64 `json:"rev"`
	Articles []struct {
		Code      string          `json:"code"`
		Title     string          `json:"title"`
		Body      json.RawMessage `json:"body"`
		WordCount int             `json:"word_count"`
		Deleted   bool            `json:"deleted"`
	} `json:"articles"`
}

func getArticles(t *testing.T, r http.Handler, query string) articlesResp {
	t.Helper()
	w := do(r, "GET", "/api/v1/articles"+query, appTok, nil)
	mustOK(t, w, 200)
	var out articlesResp
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestArticlesAreCreatedSyncedAndDeleted(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "PUT", "/admin/articles/R0001", appTok, bytes.NewBufferString(articleDoc)), 401) // the app cannot add
	w := do(r, "PUT", "/admin/articles/R0001", adminTok, bytes.NewBufferString(articleDoc))
	mustOK(t, w, 200)
	if !strings.Contains(w.Body.String(), `"word_count":11`) {
		t.Fatalf("word count not reported: %s", w.Body.String())
	}
	first := getArticles(t, r, "?since=0")
	if len(first.Articles) != 1 || first.Articles[0].Title != "A Short Story" || first.Articles[0].WordCount != 11 {
		t.Fatalf("bad list: %+v", first)
	}
	var body [][]string
	json.Unmarshal(first.Articles[0].Body, &body)
	if len(body) != 2 || body[0][1] != "The wind blew." {
		t.Fatalf("body changed on the way: %v", body)
	}
	// nothing new since the last revision
	if n := len(getArticles(t, r, "?since="+itoa(first.Rev)).Articles); n != 0 {
		t.Fatalf("expected no changes, got %d", n)
	}
	// replacing and deleting show up as changes; the deleted one has no body
	mustOK(t, do(r, "PUT", "/admin/articles/R0001", adminTok, bytes.NewBufferString(strings.Replace(articleDoc, "A Short Story", "Another Title", 1))), 200)
	changed := getArticles(t, r, "?since="+itoa(first.Rev))
	if len(changed.Articles) != 1 || changed.Articles[0].Title != "Another Title" {
		t.Fatalf("update not synced: %+v", changed)
	}
	mustOK(t, do(r, "DELETE", "/admin/articles/R0001", adminTok, nil), 204)
	gone := getArticles(t, r, "?since="+itoa(changed.Rev))
	if len(gone.Articles) != 1 || !gone.Articles[0].Deleted || string(gone.Articles[0].Body) != "[]" {
		t.Fatalf("deletion not synced: %+v", gone)
	}
	if n := len(getArticles(t, r, "?since=0").Articles); n != 0 {
		t.Fatalf("a first sync should not see deleted articles, got %d", n)
	}
	mustOK(t, do(r, "DELETE", "/admin/articles/R0099", adminTok, nil), 404)
}

func TestArticlesRejectMalformedInput(t *testing.T) {
	r, _ := newServer(t)
	for name, c := range map[string]struct{ code, body string }{
		"bad code":        {"X1", articleDoc},
		"no title":        {"R0001", `{"title":" ","body":[["Hi."]]}`},
		"no body":         {"R0001", `{"title":"T","body":[]}`},
		"empty paragraph": {"R0001", `{"title":"T","body":[[]]}`},
		"empty sentence":  {"R0001", `{"title":"T","body":[[" "]]}`},
		"not json":        {"R0001", `nope`},
	} {
		if w := do(r, "PUT", "/admin/articles/"+c.code, adminTok, bytes.NewBufferString(c.body)); w.Code != 400 {
			t.Errorf("%s: status %d, want 400", name, w.Code)
		}
	}
}

type notebookResp struct {
	Rev   int64 `json:"rev"`
	Words []struct {
		ID       string `json:"id"`
		Word     string `json:"word"`
		Sentence string `json:"sentence"`
		Meaning  string `json:"meaning"`
		State    string `json:"state"`
	} `json:"words"`
}

func getNotebook(t *testing.T, r http.Handler, query string) notebookResp {
	t.Helper()
	w := do(r, "GET", "/api/v1/notebook"+query, appTok, nil)
	mustOK(t, w, 200)
	var out notebookResp
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestNotebookWordsSyncAndRemovalsAreTombstones(t *testing.T) {
	r, _ := newServer(t)
	const id = "7d1c2b3a-0000-4000-8000-000000000001"
	send := func(state string) {
		body := `{"words":[{"id":"` + id + `","word":"wind","sentence":"The wind blew.","article":"R0001","meaning":"风","state":"` + state + `"}]}`
		mustOK(t, do(r, "PUT", "/api/v1/notebook", appTok, bytes.NewBufferString(body)), 200)
	}
	send("learning")
	first := getNotebook(t, r, "?since=0")
	if len(first.Words) != 1 || first.Words[0].Word != "wind" || first.Words[0].Meaning != "风" {
		t.Fatalf("bad notebook: %+v", first)
	}
	send("mastered")
	if got := getNotebook(t, r, "?since="+itoa(first.Rev)); len(got.Words) != 1 || got.Words[0].State != "mastered" {
		t.Fatalf("state change not synced: %+v", got)
	}
	send("none")
	if got := getNotebook(t, r, "?since="+itoa(first.Rev)); len(got.Words) != 1 || got.Words[0].State != "none" {
		t.Fatalf("removal should arrive as a tombstone: %+v", got)
	}
	if n := len(getNotebook(t, r, "?since=0").Words); n != 0 {
		t.Fatalf("a first sync should skip removed words, got %d", n)
	}
	for name, body := range map[string]string{
		"bad id":    `{"words":[{"id":"x","word":"w","sentence":"s","state":"learning"}]}`,
		"bad state": `{"words":[{"id":"` + id + `","word":"w","sentence":"s","state":"weird"}]}`,
		"no word":   `{"words":[{"id":"` + id + `","word":" ","sentence":"s","state":"learning"}]}`,
		"bad code":  `{"words":[{"id":"` + id + `","word":"w","sentence":"s","article":"zz","state":"learning"}]}`,
		"empty":     `{"words":[]}`,
	} {
		if w := do(r, "PUT", "/api/v1/notebook", appTok, bytes.NewBufferString(body)); w.Code != 400 {
			t.Errorf("%s: status %d, want 400", name, w.Code)
		}
	}
}

func TestDictionaryFileIsUploadedChecksummedAndServed(t *testing.T) {
	r, _ := newServer(t)
	mustOK(t, do(r, "GET", "/api/v1/dict", appTok, nil), 404)
	mustOK(t, do(r, "PUT", "/admin/dict", adminTok, bytes.NewBufferString("not a database")), 400)
	data := append([]byte("SQLite format 3\x00"), bytes.Repeat([]byte{7}, 5000)...)
	mustOK(t, do(r, "PUT", "/admin/dict", appTok, bytes.NewReader(data)), 401)
	mustOK(t, do(r, "PUT", "/admin/dict", adminTok, bytes.NewReader(data)), 200)

	w := do(r, "GET", "/api/v1/dict", appTok, nil)
	mustOK(t, w, 200)
	sum := sha256.Sum256(data)
	var info struct {
		Size   int64  `json:"size"`
		SHA256 string `json:"sha256"`
	}
	json.Unmarshal(w.Body.Bytes(), &info)
	if info.Size != int64(len(data)) || info.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("bad info: %+v", info)
	}
	got := do(r, "GET", "/api/v1/dict/file", appTok, nil)
	mustOK(t, got, 200)
	if !bytes.Equal(got.Body.Bytes(), data) {
		t.Fatal("file differs from what was uploaded")
	}
	part := do(r, "GET", "/api/v1/dict/file", appTok, nil, "Range", "bytes=16-31")
	mustOK(t, part, 206)
	if part.Body.Len() != 16 {
		t.Fatalf("range returned %d bytes", part.Body.Len())
	}
}

func TestReadingHelpUsesAIButSavesNothing(t *testing.T) {
	var calls int32
	var lastPrompt string
	fake := fakeAI(t, &calls, func(prompt string, w http.ResponseWriter) {
		lastPrompt = prompt
		switch {
		case strings.Contains(prompt, "Word:"):
			reply(w, `{"zh":"吹","definition":"to move air","note":"常和 wind 连用"}`)
		case strings.Contains(prompt, "hard sentences"):
			reply(w, `{"zh":"风吹着。","grammar":"主语 + 谓语","simple":"The air moved."}`)
		default:
			reply(w, "风吹着。")
		}
	})
	r, _ := newServerWith(t, func(c *Config) {
		c.AI = llm.New("secret-key", fake.URL, "test-model")
		c.AIPerDay = 5
	})

	w := do(r, "POST", "/api/v1/read/word", appTok, bytes.NewBufferString(`{"word":"blew","sentence":"The wind blew."}`))
	mustOK(t, w, 200)
	if !strings.Contains(lastPrompt, "Word: blew") || !strings.Contains(lastPrompt, "Sentence: The wind blew.") {
		t.Fatalf("prompt lacks the word or sentence: %s", lastPrompt)
	}
	if !strings.Contains(w.Body.String(), "吹") || !strings.Contains(w.Body.String(), "to move air") {
		t.Fatalf("bad answer: %s", w.Body.String())
	}
	// asking again asks the AI again: nothing is stored
	mustOK(t, do(r, "POST", "/api/v1/read/word", appTok, bytes.NewBufferString(`{"word":"blew","sentence":"The wind blew."}`)), 200)
	if calls != 2 {
		t.Fatalf("AI called %d times, want 2 (no caching)", calls)
	}
	s := do(r, "POST", "/api/v1/read/sentence", appTok, bytes.NewBufferString(`{"sentence":"The wind blew."}`))
	mustOK(t, s, 200)
	if !strings.Contains(s.Body.String(), "主语") || !strings.Contains(s.Body.String(), "The air moved.") {
		t.Fatalf("bad sentence answer: %s", s.Body.String())
	}
	tr := do(r, "POST", "/api/v1/read/translate", appTok, bytes.NewBufferString(`{"text":"The wind blew."}`))
	mustOK(t, tr, 200)
	if !strings.Contains(tr.Body.String(), "风吹着") {
		t.Fatalf("bad translation: %s", tr.Body.String())
	}
	// the daily limit still applies: the 5th call goes through, the 6th is refused
	mustOK(t, do(r, "POST", "/api/v1/read/translate", appTok, bytes.NewBufferString(`{"text":"Hi"}`)), 200)
	mustOK(t, do(r, "POST", "/api/v1/read/translate", appTok, bytes.NewBufferString(`{"text":"Hi"}`)), 429)
}

func TestReadingHelpRejectsBadRequestsAndWorksOnlyWithAI(t *testing.T) {
	r, _ := newServer(t) // no AI key
	mustOK(t, do(r, "POST", "/api/v1/read/word", appTok, bytes.NewBufferString(`{"word":"a","sentence":"b"}`)), 503)
	mustOK(t, do(r, "POST", "/api/v1/read/word", "", bytes.NewBufferString(`{"word":"a","sentence":"b"}`)), 401)

	var calls int32
	fake := fakeAI(t, &calls, func(_ string, w http.ResponseWriter) { reply(w, `{"zh":"","definition":""}`) })
	r2, _ := newServerWith(t, func(c *Config) { c.AI = llm.New("secret-key", fake.URL, "m") })
	mustOK(t, do(r2, "POST", "/api/v1/read/word", appTok, bytes.NewBufferString(`{"word":"","sentence":"b"}`)), 400)
	mustOK(t, do(r2, "POST", "/api/v1/read/sentence", appTok, bytes.NewBufferString(`{"sentence":"`+strings.Repeat("a", 2000)+`"}`)), 400)
	mustOK(t, do(r2, "POST", "/api/v1/read/word", appTok, bytes.NewBufferString(`{"word":"a","sentence":"b"}`)), 502) // empty answer
}
