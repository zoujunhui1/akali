// Package llm talks to DeepSeek's OpenAI-style chat API. The key lives only on the server.
package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

var ErrNotConfigured = errors.New("AI is not configured on the server")

type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type Client struct {
	key, url, model string
	http            *http.Client
}

// New returns nil when no key is set, so callers can treat "no AI" as a normal state.
func New(key, baseURL, model string) *Client {
	if key == "" {
		return nil
	}
	if baseURL == "" {
		baseURL = "https://api.deepseek.com"
	}
	if model == "" {
		model = "deepseek-flash"
	}
	return &Client{key: key, url: strings.TrimRight(baseURL, "/"), model: model, http: &http.Client{Timeout: 90 * time.Second}}
}

type request struct {
	Model          string            `json:"model"`
	Messages       []Message         `json:"messages"`
	MaxTokens      int               `json:"max_tokens"`
	ResponseFormat map[string]string `json:"response_format,omitempty"`
}

// Chat sends the messages and returns the reply text. With jsonMode the model is asked for one JSON object
// (the prompt must mention "json").
func (c *Client) Chat(ctx context.Context, msgs []Message, maxTokens int, jsonMode bool) (string, error) {
	if c == nil {
		return "", ErrNotConfigured
	}
	body := request{Model: c.model, Messages: msgs, MaxTokens: maxTokens}
	if jsonMode {
		body.ResponseFormat = map[string]string{"type": "json_object"}
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url+"/chat/completions", bytes.NewReader(raw))
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+c.key)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("AI service unreachable: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("AI service returned %d", resp.StatusCode) // never echo the body: it may mention the key
	}
	var out struct {
		Choices []struct {
			Message Message `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &out); err != nil || len(out.Choices) == 0 {
		return "", errors.New("AI service sent an unreadable answer")
	}
	text := strings.TrimSpace(out.Choices[0].Message.Content)
	if text == "" {
		return "", errors.New("AI service sent an empty answer")
	}
	return text, nil
}
