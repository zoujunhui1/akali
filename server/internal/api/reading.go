package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/llm"
	"github.com/zoujunhui1/akali/server/internal/store"
)

const (
	maxArticleChars   = 400_000
	maxNotebookPerPut = 500
	maxDictBytes      = 64 << 20
	maxAskChars       = 1500
)

func (s *Server) readingRoutes(app, admin *gin.RouterGroup) {
	app.GET("/articles", s.articleList)
	admin.PUT("/articles/:code", s.articlePut)
	admin.DELETE("/articles/:code", s.articleDelete)

	app.GET("/notebook", s.notebookList)
	app.PUT("/notebook", s.notebookPut)

	app.GET("/dict", s.dictInfo)
	app.GET("/dict/file", s.dictFile)
	admin.PUT("/dict", s.dictPut)

	// Reading help from AI. The answers are never saved anywhere.
	app.POST("/read/word", s.readWord)
	app.POST("/read/sentence", s.readSentence)
	app.POST("/read/translate", s.readTranslate)
}

type articleJSON struct {
	Code      string          `json:"code"`
	Title     string          `json:"title"`
	Source    string          `json:"source"`
	Body      json.RawMessage `json:"body"` // [[sentence, ...], ...]
	WordCount int             `json:"word_count"`
	Deleted   bool            `json:"deleted"`
	CreatedAt int64           `json:"created_at"` // unix milliseconds
}

func sinceParam(c *gin.Context) (int64, bool) {
	var since int64
	if v := c.Query("since"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &since); err != nil || since < 0 {
			fail(c, http.StatusBadRequest, errors.New("since must be a non-negative integer"))
			return 0, false
		}
	}
	return since, true
}

// GET /api/v1/articles?since=N
func (s *Server) articleList(c *gin.Context) {
	since, ok := sinceParam(c)
	if !ok {
		return
	}
	list, rev, err := s.store.ArticleChanges(c.Request.Context(), since)
	if err != nil {
		storeFail(c, err)
		return
	}
	out := make([]articleJSON, 0, len(list))
	for _, a := range list {
		body := a.Body
		if a.Deleted {
			body = json.RawMessage("[]")
		}
		out = append(out, articleJSON{
			Code: a.Code, Title: a.Title, Source: a.Source, Body: body, WordCount: a.WordCount,
			Deleted: a.Deleted, CreatedAt: a.CreatedAt.UnixMilli(),
		})
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev, "articles": out})
}

// validBody checks [[sentence, ...], ...] and counts the words in it.
func validBody(raw json.RawMessage) (words int, err error) {
	var paragraphs [][]string
	if err := json.Unmarshal(raw, &paragraphs); err != nil || len(paragraphs) == 0 {
		return 0, errors.New("body must be a non-empty list of paragraphs, each a list of sentences")
	}
	chars := 0
	for _, p := range paragraphs {
		if len(p) == 0 {
			return 0, errors.New("a paragraph has no sentences")
		}
		for _, sentence := range p {
			if strings.TrimSpace(sentence) == "" {
				return 0, errors.New("a sentence is empty")
			}
			chars += utf8.RuneCountInString(sentence)
			words += len(strings.Fields(sentence))
		}
	}
	if chars > maxArticleChars {
		return 0, fmt.Errorf("the article is longer than %d characters", maxArticleChars)
	}
	return words, nil
}

// PUT /admin/articles/:code  {title, source, body}
func (s *Server) articlePut(c *gin.Context) {
	code := c.Param("code")
	if !store.ValidArticleCode(code) {
		fail(c, http.StatusBadRequest, errors.New("article code must look like R0007"))
		return
	}
	var b struct {
		Title  string          `json:"title"`
		Source string          `json:"source"`
		Body   json.RawMessage `json:"body"`
	}
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	title := strings.TrimSpace(b.Title)
	if title == "" || utf8.RuneCountInString(title) > 200 || utf8.RuneCountInString(b.Source) > 500 {
		fail(c, http.StatusBadRequest, errors.New("title is required (≤200 characters) and source must be ≤500 characters"))
		return
	}
	words, err := validBody(b.Body)
	if err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if err := s.store.PutArticle(c.Request.Context(), store.Article{Code: code, Title: title, Source: b.Source, Body: b.Body, WordCount: words}); err != nil {
		storeFail(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": code, "word_count": words})
}

// DELETE /admin/articles/:code
func (s *Server) articleDelete(c *gin.Context) {
	code := c.Param("code")
	if !store.ValidArticleCode(code) {
		fail(c, http.StatusBadRequest, errors.New("article code must look like R0007"))
		return
	}
	if err := s.store.DeleteArticle(c.Request.Context(), code); err != nil {
		storeFail(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

type notebookJSON struct {
	ID       string `json:"id"`
	Word     string `json:"word"`
	Sentence string `json:"sentence"`
	Article  string `json:"article"`
	Meaning  string `json:"meaning"`
	State    string `json:"state"` // "learning", "mastered" or "none"
}

// GET /api/v1/notebook?since=N
func (s *Server) notebookList(c *gin.Context) {
	since, ok := sinceParam(c)
	if !ok {
		return
	}
	list, rev, err := s.store.NotebookChanges(c.Request.Context(), since)
	if err != nil {
		storeFail(c, err)
		return
	}
	out := make([]notebookJSON, 0, len(list))
	for _, w := range list {
		out = append(out, notebookJSON{ID: w.ID, Word: w.Word, Sentence: w.Sentence, Article: w.Article, Meaning: w.Meaning, State: w.State})
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev, "words": out})
}

// PUT /api/v1/notebook  {words:[{id, word, sentence, article, meaning, state}]}  ->  {rev}
func (s *Server) notebookPut(c *gin.Context) {
	var b struct {
		Words []notebookJSON `json:"words"`
	}
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if len(b.Words) == 0 || len(b.Words) > maxNotebookPerPut {
		fail(c, http.StatusBadRequest, fmt.Errorf("send between 1 and %d words", maxNotebookPerPut))
		return
	}
	words := make([]store.NotebookWord, 0, len(b.Words))
	for i, w := range b.Words {
		if !store.ValidNotebookID(w.ID) || !store.ValidNotebookState(w.State) ||
			strings.TrimSpace(w.Word) == "" || utf8.RuneCountInString(w.Word) > 60 ||
			utf8.RuneCountInString(w.Sentence) > 1000 || utf8.RuneCountInString(w.Meaning) > 400 ||
			(w.Article != "" && !store.ValidArticleCode(w.Article)) {
			fail(c, http.StatusBadRequest, fmt.Errorf("word %d is malformed", i))
			return
		}
		words = append(words, store.NotebookWord{ID: w.ID, Word: w.Word, Sentence: w.Sentence, Article: w.Article, Meaning: w.Meaning, State: w.State})
	}
	rev, err := s.store.SetNotebook(c.Request.Context(), words)
	if err != nil {
		storeFail(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"rev": rev})
}

func (s *Server) dictPath() string { return filepath.Join(s.cfg.AudioDir, "dict", "dict.db") }

func fileSHA(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// GET /api/v1/dict  ->  {size, sha256}; 404 until a dictionary has been uploaded
func (s *Server) dictInfo(c *gin.Context) {
	sha, size, err := fileSHA(s.dictPath())
	if err != nil {
		fail(c, http.StatusNotFound, errors.New("还没有上传字典"))
		return
	}
	c.JSON(http.StatusOK, gin.H{"size": size, "sha256": sha})
}

// GET /api/v1/dict/file (supports Range)
func (s *Server) dictFile(c *gin.Context) {
	f, err := os.Open(s.dictPath())
	if err != nil {
		fail(c, http.StatusNotFound, errors.New("还没有上传字典"))
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	c.Header("Content-Type", "application/octet-stream")
	http.ServeContent(c.Writer, c.Request, "dict.db", info.ModTime(), f)
}

// PUT /admin/dict  (raw bytes of the SQLite file, up to 64 MB)
func (s *Server) dictPut(c *gin.Context) {
	body := http.MaxBytesReader(c.Writer, c.Request.Body, maxDictBytes)
	dir := filepath.Dir(s.dictPath())
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	tmp, err := os.CreateTemp(dir, "dict-*.tmp")
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	defer os.Remove(tmp.Name())
	head := make([]byte, 16)
	n, _ := io.ReadFull(body, head)
	if string(head[:n]) != "SQLite format 3\x00" {
		tmp.Close()
		fail(c, http.StatusBadRequest, errors.New("that is not a SQLite file"))
		return
	}
	if _, err := tmp.Write(head[:n]); err != nil {
		tmp.Close()
		fail(c, http.StatusInternalServerError, err)
		return
	}
	if _, err := io.Copy(tmp, body); err != nil {
		tmp.Close()
		fail(c, http.StatusRequestEntityTooLarge, err)
		return
	}
	if err := tmp.Close(); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	if err := os.Rename(tmp.Name(), s.dictPath()); err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	sha, size, err := fileSHA(s.dictPath())
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"size": size, "sha256": sha})
}

// ---- AI help for reading: nothing here is stored ----

const readWordPrompt = `You help an adult Chinese speaker (English level B1-B2) read English articles.
Given a word and the sentence it appears in, answer with ONE json object with exactly these keys:
"zh": the Chinese meaning of the word AS USED IN THIS SENTENCE (2-12 Chinese characters),
"definition": one short English explanation in simple words, for this use,
"note": one short Chinese remark about how it is used here (collocation, tone, or a common trap); use "" if there is nothing useful.
Output only the json object.`

const readSentencePrompt = `You help an adult Chinese speaker (English level B1-B2) understand hard sentences in English articles.
Given one sentence or a short passage, answer with ONE json object with exactly these keys:
"zh": a natural Chinese translation,
"grammar": a short Chinese explanation of the structure (main clause, subordinate clauses, key phrases; for a passage, only the hardest points), at most 5 short lines separated by \n,
"simple": the same meaning in simpler English (a short passage gets a short paraphrase).
Output only the json object.`

const readTranslatePrompt = `Translate the English text into natural Chinese. Output only the Chinese translation, nothing else.`

func asked(c *gin.Context, fields ...*string) bool {
	for _, f := range fields {
		*f = strings.TrimSpace(*f)
		if *f == "" || utf8.RuneCountInString(*f) > maxAskChars {
			fail(c, http.StatusBadRequest, fmt.Errorf("text must be between 1 and %d characters", maxAskChars))
			return false
		}
	}
	return true
}

// askJSON asks the model for a json object and returns it as a generic map of strings.
func (s *Server) askJSON(c *gin.Context, system, user string, maxTokens int, keys ...string) (map[string]string, bool) {
	reply, err := s.cfg.AI.Chat(c.Request.Context(), []llm.Message{{Role: "system", Content: system}, {Role: "user", Content: user}}, maxTokens, true)
	if err != nil {
		aiFail(c, err)
		return nil, false
	}
	var out map[string]string
	if json.Unmarshal([]byte(reply), &out) != nil {
		aiFail(c, errors.New("AI 返回的格式不对"))
		return nil, false
	}
	for _, k := range keys {
		out[k] = strings.TrimSpace(out[k])
	}
	return out, true
}

// POST /api/v1/read/word  {word, sentence}  ->  {zh, definition, note}
func (s *Server) readWord(c *gin.Context) {
	var b struct{ Word, Sentence string }
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if !asked(c, &b.Word, &b.Sentence) || !s.ai(c) {
		return
	}
	out, ok := s.askJSON(c, readWordPrompt, fmt.Sprintf("Word: %s\nSentence: %s", b.Word, b.Sentence), 300, "zh", "definition", "note")
	if !ok {
		return
	}
	if out["zh"] == "" || out["definition"] == "" {
		aiFail(c, errors.New("AI 返回的内容不完整"))
		return
	}
	c.JSON(http.StatusOK, gin.H{"zh": out["zh"], "definition": out["definition"], "note": out["note"]})
}

// POST /api/v1/read/sentence  {sentence}  ->  {zh, grammar, simple}
func (s *Server) readSentence(c *gin.Context) {
	var b struct{ Sentence string }
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if !asked(c, &b.Sentence) || !s.ai(c) {
		return
	}
	out, ok := s.askJSON(c, readSentencePrompt, b.Sentence, 900, "zh", "grammar", "simple")
	if !ok {
		return
	}
	if out["zh"] == "" {
		aiFail(c, errors.New("AI 返回的内容不完整"))
		return
	}
	c.JSON(http.StatusOK, gin.H{"zh": out["zh"], "grammar": out["grammar"], "simple": out["simple"]})
}

// POST /api/v1/read/translate  {text}  ->  {zh}
func (s *Server) readTranslate(c *gin.Context) {
	var b struct{ Text string }
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if !asked(c, &b.Text) || !s.ai(c) {
		return
	}
	reply, err := s.cfg.AI.Chat(c.Request.Context(), []llm.Message{{Role: "system", Content: readTranslatePrompt}, {Role: "user", Content: b.Text}}, 900, false)
	if err != nil {
		aiFail(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"zh": strings.TrimSpace(reply), "at": time.Now().UnixMilli()})
}
