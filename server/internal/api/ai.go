package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/server/internal/llm"
	"github.com/zoujunhui1/akali/server/internal/store"
)

// limiter caps AI calls per day so a leaked app token cannot run up the bill.
type limiter struct {
	mu    sync.Mutex
	day   string
	count int
	max   int
}

func todayUTC() string { return time.Now().UTC().Format("2006-01-02") }

func (l *limiter) allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	today := todayUTC()
	if l.day != today {
		l.day, l.count = today, 0
	}
	if l.count >= l.max {
		return false
	}
	l.count++
	return true
}

func (s *Server) aiRoutes(app *gin.RouterGroup) {
	app.POST("/vocab/:code/entries/:key/explain", s.explain)
	app.POST("/vocab/:code/chat", s.chat)
}

// ai checks that AI is set up and the day's budget is left; it answers the error itself.
func (s *Server) ai(c *gin.Context) bool {
	if s.cfg.AI == nil {
		fail(c, http.StatusServiceUnavailable, errors.New("服务器还没有配置 AI"))
		return false
	}
	if !s.limit.allow() {
		fail(c, http.StatusTooManyRequests, errors.New("今天的 AI 次数用完了，明天再来"))
		return false
	}
	return true
}

func aiFail(c *gin.Context, err error) {
	fail(c, http.StatusBadGateway, fmt.Errorf("AI 暂时不可用：%s", err))
}

type note struct {
	ZH        string `json:"zh"`
	Def       string `json:"definition"`
	Example   string `json:"example"`
	ExampleZH string `json:"example_zh"`
	At        int64  `json:"at"` // unix milliseconds
}

const explainPrompt = `You help an adult Chinese speaker (English level B1-B2) learn words from the Oxford Picture Dictionary.
Given a topic and one entry (a word, a phrase, or a short action sentence), answer with ONE json object with exactly these keys:
"zh": the Chinese meaning, short and natural (2-12 Chinese characters),
"definition": one short English explanation in simple words,
"example": one natural example sentence that uses the entry,
"example_zh": the Chinese translation of the example.
Use the meaning that fits the topic. Output only the json object, for example:
{"zh":"红色","definition":"the color of blood","example":"She is wearing a red dress.","example_zh":"她穿着一条红色的裙子。"}`

// POST /api/v1/vocab/:code/entries/:key/explain[?force=1]
// Explains one entry with AI the first time it is asked, then serves the saved answer.
func (s *Server) explain(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	key := c.Param("key")
	ctx := c.Request.Context()
	l, err := s.store.GetVocab(ctx, unit, number)
	if err != nil {
		storeFail(c, err)
		return
	}
	text, found := entryText(l.Entries, key)
	if !found {
		fail(c, http.StatusNotFound, errors.New("no such entry"))
		return
	}
	if c.Query("force") != "1" {
		var notes map[string]json.RawMessage
		if json.Unmarshal(l.Notes, &notes) == nil && notes[key] != nil {
			c.Data(http.StatusOK, "application/json; charset=utf-8", notes[key])
			return
		}
	}
	if !s.ai(c) {
		return
	}
	reply, err := s.cfg.AI.Chat(ctx, []llm.Message{
		{Role: "system", Content: explainPrompt},
		{Role: "user", Content: fmt.Sprintf("Topic: %s\nEntry: %s", l.Title, text)},
	}, 400, true)
	if err != nil {
		aiFail(c, err)
		return
	}
	n, err := parseNote(reply)
	if err != nil {
		aiFail(c, err)
		return
	}
	n.At = time.Now().UnixMilli()
	raw, _ := json.Marshal(n)
	if err := s.store.SetVocabNote(ctx, unit, number, key, raw); err != nil {
		storeFail(c, err)
		return
	}
	c.Data(http.StatusOK, "application/json; charset=utf-8", raw)
}

func parseNote(reply string) (note, error) {
	var n note
	if err := json.Unmarshal([]byte(reply), &n); err != nil {
		return note{}, errors.New("AI 返回的格式不对")
	}
	for _, f := range []*string{&n.ZH, &n.Def, &n.Example, &n.ExampleZH} {
		*f = strings.TrimSpace(*f)
		if *f == "" || len([]rune(*f)) > 300 {
			return note{}, errors.New("AI 返回的内容不完整")
		}
	}
	return n, nil
}

// entryText finds the entry whose key ("<page>-<label>", the name of its audio file) matches.
func entryText(entries json.RawMessage, key string) (string, bool) {
	var list []struct {
		Label string `json:"label"`
		Text  string `json:"text"`
		Page  int    `json:"page"`
	}
	if json.Unmarshal(entries, &list) != nil {
		return "", false
	}
	for _, e := range list {
		if fmt.Sprintf("%d-%s", e.Page, e.Label) == key {
			return e.Text, true
		}
	}
	return "", false
}

const (
	maxChatTurns = 20
	maxChatChars = 600
)

type chatBody struct {
	Messages []llm.Message `json:"messages"`
}

// POST /api/v1/vocab/:code/chat  {messages:[{role:"user"|"assistant", content}]}  ->  {reply}
func (s *Server) chat(c *gin.Context) {
	unit, number, ok := vocabCode(c)
	if !ok {
		return
	}
	var b chatBody
	if err := c.ShouldBindJSON(&b); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}
	if n := len(b.Messages); n > maxChatTurns {
		b.Messages = b.Messages[n-maxChatTurns:]
	}
	if len(b.Messages) == 0 || b.Messages[len(b.Messages)-1].Role != "user" {
		fail(c, http.StatusBadRequest, errors.New("the last message must be from the user"))
		return
	}
	for i, m := range b.Messages {
		if (m.Role != "user" && m.Role != "assistant") || strings.TrimSpace(m.Content) == "" || len([]rune(m.Content)) > maxChatChars {
			fail(c, http.StatusBadRequest, fmt.Errorf("message %d is not acceptable", i))
			return
		}
	}
	ctx := c.Request.Context()
	l, err := s.store.GetVocab(ctx, unit, number)
	if err != nil {
		storeFail(c, err)
		return
	}
	if !s.ai(c) {
		return
	}
	reply, err := s.cfg.AI.Chat(ctx, append([]llm.Message{{Role: "system", Content: chatPrompt(l)}}, b.Messages...), 500, false)
	if err != nil {
		aiFail(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"reply": reply})
}

func chatPrompt(l store.VocabLesson) string {
	var words []string
	var list []struct {
		Text string `json:"text"`
	}
	if json.Unmarshal(l.Entries, &list) == nil {
		for _, e := range list {
			words = append(words, e.Text)
		}
	}
	return fmt.Sprintf(`You are a friendly English conversation partner for an adult Chinese speaker (level B1-B2) who is learning vocabulary from the Oxford Picture Dictionary.
Today's topic: %s.
Words from this topic: %s.
Rules: talk in simple, natural English; keep each reply to 1-3 short sentences; use words from the topic and end with a short question to keep the chat going.
If the learner makes a mistake, first answer normally, then add one line "Better: ..." with the corrected sentence.
If the learner writes in Chinese or asks what something means, answer in Chinese.`, l.Title, strings.Join(words, "; "))
}
