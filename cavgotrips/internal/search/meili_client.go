package search

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	defaultMeiliTimeout   = 5 * time.Second
	meiliTaskPollInterval = 200 * time.Millisecond
	meiliTaskPollTimeout  = 60 * time.Second
)

// MeiliClient is a minimal Meilisearch REST client built on net/http, so the
// service gains no new Go dependencies.
type MeiliClient struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client
}

func NewMeiliClient(baseURL, apiKey string, timeout time.Duration) *MeiliClient {
	if timeout <= 0 {
		timeout = defaultMeiliTimeout
	}
	return &MeiliClient{
		baseURL:    strings.TrimRight(baseURL, "/"),
		apiKey:     apiKey,
		httpClient: &http.Client{Timeout: timeout},
	}
}

// indexSettings holds the write-once index configuration.
type indexSettings struct {
	UID           string
	PrimaryKey    string
	Searchable    []string
	Filterable    []string
	RankingRules  []string
	TypoTolerance *bool
}

type meiliSearchRequest struct {
	Q                    string   `json:"q"`
	Offset               int      `json:"offset"`
	Limit                int      `json:"limit"`
	Filter               *string  `json:"filter,omitempty"`
	AttributesToSearchOn []string `json:"attributesToSearchOn,omitempty"`
}

type meiliSearchResponse struct {
	Hits               []json.RawMessage `json:"hits"`
	EstimatedTotalHits int64             `json:"estimatedTotalHits"`
}

type meiliStats struct {
	NumberOfDocuments int64 `json:"numberOfDocuments"`
}

type meiliTask struct {
	TaskUID int    `json:"taskUid"`
	Status  string `json:"status"`
	Error   *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Health verifies the Meilisearch server is available.
func (c *MeiliClient) Health(ctx context.Context) error {
	var out struct {
		Status string `json:"status"`
	}
	if err := c.do(ctx, http.MethodGet, "/health", nil, &out); err != nil {
		return err
	}
	if out.Status != "available" {
		return fmt.Errorf("meilisearch unhealthy: status=%q", out.Status)
	}
	return nil
}

// Stats returns the number of documents in an index (0 if the index does not
// exist yet).
func (c *MeiliClient) Stats(ctx context.Context, uid string) (int64, error) {
	var out meiliStats
	if err := c.do(ctx, http.MethodGet, "/indexes/"+uid+"/stats", nil, &out); err != nil {
		var api meiliAPIError
		if parseMeiliError(err, &api) && api.Code == "index_not_found" {
			return 0, nil
		}
		return 0, err
	}
	return out.NumberOfDocuments, nil
}

// EnsureIndex makes sure the index exists with the desired settings, then
// applies the settings (settings updates are idempotent and safe to repeat).
func (c *MeiliClient) EnsureIndex(ctx context.Context, cfg indexSettings) error {
	if err := c.CreateIndex(ctx, cfg.UID, cfg.PrimaryKey); err != nil {
		var api meiliAPIError
		if !(parseMeiliError(err, &api) && api.Code == "index_already_exists") {
			return err
		}
	}
	body := map[string]any{
		"searchableAttributes": cfg.Searchable,
		"filterableAttributes": cfg.Filterable,
		"rankingRules":         cfg.RankingRules,
	}
	if cfg.TypoTolerance != nil {
		body["typoTolerance"] = *cfg.TypoTolerance
	}
	var task meiliTask
	if err := c.do(ctx, http.MethodPut, "/indexes/"+cfg.UID+"/settings", body, &task); err != nil {
		return err
	}
	return c.waitForTask(ctx, task)
}

// CreateIndex creates the index with the given primary key. A 400
// "index already exists" is short-circuited to that typed error.
func (c *MeiliClient) CreateIndex(ctx context.Context, uid, primaryKey string) error {
	body := map[string]string{"uid": uid, "primaryKey": primaryKey}
	return c.do(ctx, http.MethodPost, "/indexes", body, nil)
}

// Search runs a single search request and returns raw hit documents.
func (c *MeiliClient) Search(ctx context.Context, uid string, req meiliSearchRequest) (meiliSearchResponse, error) {
	if req.Limit <= 0 {
		req.Limit = 20
	}
	var out meiliSearchResponse
	if err := c.do(ctx, http.MethodPost, "/indexes/"+uid+"/search", req, &out); err != nil {
		return out, err
	}
	return out, nil
}

// SearchAll polls the index in batches until the response is exhausted or the
// cap is reached, returning every matching raw document plus the estimated
// total. Used by providers that must post-filter in memory.
func (c *MeiliClient) SearchAll(ctx context.Context, uid, q string, attrs []string, filter *string, cap int) ([]json.RawMessage, int64, error) {
	var all []json.RawMessage
	var estimate int64
	const batch = 1000
	offset := 0
	for {
		resp, err := c.Search(ctx, uid, meiliSearchRequest{
			Q:                    q,
			Offset:               offset,
			Limit:                batch,
			Filter:               filter,
			AttributesToSearchOn: attrs,
		})
		if err != nil {
			return nil, 0, err
		}
		if len(resp.Hits) == 0 {
			estimate = resp.EstimatedTotalHits
			break
		}
		all = append(all, resp.Hits...)
		estimate = resp.EstimatedTotalHits
		offset += len(resp.Hits)
		if cap > 0 && len(all) >= cap {
			all = all[:cap]
			break
		}
		if len(resp.Hits) < batch {
			break
		}
	}
	return all, estimate, nil
}

// AddDocuments indexes documents (create-or-update by primary key) and waits
// for the indexing task to complete.
func (c *MeiliClient) AddDocuments(ctx context.Context, uid string, docs []any) error {
	if len(docs) == 0 {
		return nil
	}
	var task meiliTask
	if err := c.do(ctx, http.MethodPost, "/indexes/"+uid+"/documents", docs, &task); err != nil {
		return err
	}
	return c.waitForTask(ctx, task)
}

// DeleteBatch removes documents by primary key and waits for completion.
func (c *MeiliClient) DeleteBatch(ctx context.Context, uid string, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	var task meiliTask
	if err := c.do(ctx, http.MethodPost, "/indexes/"+uid+"/documents/delete-batch", map[string]any{"ids": ids}, &task); err != nil {
		return err
	}
	return c.waitForTask(ctx, task)
}

// DeleteAllDocuments empties an index.
func (c *MeiliClient) DeleteAllDocuments(ctx context.Context, uid string) error {
	var task meiliTask
	if err := c.do(ctx, http.MethodDelete, "/indexes/"+uid+"/documents", nil, &task); err != nil {
		return err
	}
	return c.waitForTask(ctx, task)
}

func (c *MeiliClient) waitForTask(ctx context.Context, initial meiliTask) error {
	if initial.Status == "succeeded" {
		return nil
	}
	deadline := time.Now().Add(meiliTaskPollTimeout)
	for {
		var task meiliTask
		if err := c.do(ctx, http.MethodGet, "/tasks/"+strconv.Itoa(initial.TaskUID), nil, &task); err != nil {
			return err
		}
		switch task.Status {
		case "succeeded":
			return nil
		case "failed", "canceled":
			msg := "unknown"
			if task.Error != nil {
				msg = task.Error.Message
			}
			return fmt.Errorf("meilisearch task %s: %s", task.Status, msg)
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("meilisearch task %d timed out", task.TaskUID)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(meiliTaskPollInterval):
		}
	}
}

type meiliAPIError struct {
	Message string `json:"message"`
	Code    string `json:"code"`
	Type    string `json:"type"`
}

func (e meiliAPIError) Error() string { return fmt.Sprintf("%s (%s): %s", e.Type, e.Code, e.Message) }

func parseMeiliError(err error, out *meiliAPIError) bool {
	asMeili, ok := err.(meiliAPIError)
	if !ok {
		return false
	}
	*out = asMeili
	return true
}

func (c *MeiliClient) do(ctx context.Context, method, path string, body, out any) error {
	var bodyReader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return err
		}
		bodyReader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bodyReader)
	if err != nil {
		return err
	}
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var api meiliAPIError
		if json.Unmarshal(raw, &api) == nil && api.Code != "" {
			return api
		}
		return fmt.Errorf("meilisearch %s %s: status %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	if out != nil {
		return json.Unmarshal(raw, out)
	}
	return nil
}
