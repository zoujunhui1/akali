// Package akali talks to the phone-facing server's /api and /admin endpoints, the way the Mac tools did.
package akali

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client needs the server address, the admin token (writes) and the app token (reads).
type Client struct {
	Server     string
	AdminToken string
	AppToken   string
	HTTP       *http.Client
}

type Article struct {
	Code      string `json:"code"`
	Title     string `json:"title"`
	Source    string `json:"source"`
	WordCount int    `json:"word_count"`
}

func (c *Client) Configured() bool { return c.Server != "" && c.AdminToken != "" && c.AppToken != "" }

func (c *Client) do(ctx context.Context, method, path, token string) ([]byte, error) {
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.Server, "/")+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("%s %s -> %d", method, path, resp.StatusCode)
	}
	return data, nil
}

// Articles lists the articles the server has (deleted ones left out), oldest code first.
func (c *Client) Articles(ctx context.Context) ([]Article, error) {
	data, err := c.do(ctx, http.MethodGet, "/api/v1/articles?since=0", c.AppToken)
	if err != nil {
		return nil, err
	}
	var out struct {
		Articles []struct {
			Article
			Deleted bool `json:"deleted"`
		} `json:"articles"`
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	list := []Article{}
	for _, a := range out.Articles {
		if !a.Deleted {
			list = append(list, a.Article)
		}
	}
	return list, nil
}

func (c *Client) DeleteArticle(ctx context.Context, code string) error {
	_, err := c.do(ctx, http.MethodDelete, "/admin/articles/"+code, c.AdminToken)
	return err
}
