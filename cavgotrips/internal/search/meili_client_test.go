package search

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeMeili is a minimal Meilisearch stand-in for client tests.
type fakeMeili struct {
	mu                sync.Mutex
	searchable        string
	settingsPUT       bool
	taskStatus        atomic.Value // "succeeded" | "failed"
	addDocs           int
	allowSearch       bool
	statsIndexMissing bool
	searchCalls       int
	sawFilter         bool
	sawAttrs          bool
}

func newFakeMeili() *fakeMeili {
	f := &fakeMeili{}
	f.taskStatus.Store("succeeded")
	return f
}

func (f *fakeMeili) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.URL.Path == "/health" {
		json.NewEncoder(w).Encode(map[string]string{"status": "available"})
		return
	}
	if r.Method == http.MethodPost && r.URL.Path == "/indexes" {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"code": "index_already_exists", "message": "Index `x` already exists."})
		return
	}
	if r.Method == http.MethodPut && strings.HasSuffix(r.URL.Path, "/settings") {
		f.mu.Lock()
		f.settingsPUT = true
		f.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{"taskUid": 42, "status": f.taskStatus.Load().(string)})
		return
	}
	if strings.HasSuffix(r.URL.Path, "/documents") && r.Method == http.MethodDelete {
		json.NewEncoder(w).Encode(map[string]any{"taskUid": 43, "status": f.taskStatus.Load().(string)})
		return
	}
	if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/documents") {
		f.mu.Lock()
		f.addDocs++
		f.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{"taskUid": 44, "status": f.taskStatus.Load().(string)})
		return
	}
	if strings.HasSuffix(r.URL.Path, "/stats") {
		if f.statsIndexMissing {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]string{"code": "index_not_found", "message": "Index `x` not found."})
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"numberOfDocuments": 7})
		return
	}
	if strings.HasSuffix(r.URL.Path, "/search") && r.Method == http.MethodPost {
		f.mu.Lock()
		f.searchCalls++
		f.mu.Unlock()
		var req meiliSearchRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if req.Filter != nil {
			f.sawFilter = true
		}
		if len(req.AttributesToSearchOn) > 0 {
			f.sawAttrs = true
		}
		if !f.allowSearch {
			w.WriteHeader(http.StatusBadGateway)
			json.NewEncoder(w).Encode(map[string]any{"message": "search unavailable"})
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"hits":               []map[string]any{{"id": 1, "custom_name": "Kigali"}},
			"estimatedTotalHits": 1,
		})
		return
	}
	if r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/tasks/") {
		json.NewEncoder(w).Encode(map[string]any{"taskUid": 44, "status": f.taskStatus.Load().(string)})
		return
	}
	w.WriteHeader(http.StatusNotFound)
	json.NewEncoder(w).Encode(map[string]string{"message": "not found"})
}

func TestClient_Search(t *testing.T) {
	f := newFakeMeili()
	f.allowSearch = true
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "test-key", time.Second)

	resp, err := c.Search(context.Background(), entityLocations, meiliSearchRequest{Q: "kigali", Offset: 0, Limit: 20})
	if err != nil {
		t.Fatal(err)
	}
	if resp.EstimatedTotalHits != 1 || len(resp.Hits) != 1 {
		t.Fatalf("hits/estimate wrong: %+v", resp)
	}
	if !strings.Contains(string(resp.Hits[0]), "Kigali") {
		t.Fatalf("hit payload wrong: %s", resp.Hits[0])
	}
}

func TestClient_HealthOK(t *testing.T) {
	f := newFakeMeili()
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "", time.Second)

	if err := c.Health(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestClient_StatsMissingIndexReturnsZero(t *testing.T) {
	f := newFakeMeili()
	f.statsIndexMissing = true
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "", time.Second)

	n, err := c.Stats(context.Background(), entityLocations)
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("missing index should report 0, got %d", n)
	}
}

func TestClient_StatsPresent(t *testing.T) {
	f := newFakeMeili()
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "", time.Second)

	n, err := c.Stats(context.Background(), entityLocations)
	if err != nil || n != 7 {
		t.Fatalf("stats = (%d, %v)", n, err)
	}
}

func TestClient_EnsureIndexIdempotent(t *testing.T) {
	f := newFakeMeili()
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "key", time.Second)

	cfg := indexConfigs()[0]
	if err := c.EnsureIndex(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	put := f.settingsPUT
	f.mu.Unlock()
	if !put {
		t.Fatal("settings must be PUT after create")
	}
}

func TestClient_AddDocumentsWaitsForSucceededTask(t *testing.T) {
	f := newFakeMeili()
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "key", time.Second)

	f.taskStatus.Store("processing")
	go func() {
		time.Sleep(50 * time.Millisecond)
		f.taskStatus.Store("succeeded")
	}()
	if err := c.AddDocuments(context.Background(), entityLocations, []any{map[string]any{"id": 1}}); err != nil {
		t.Fatal(err)
	}
	if f.addDocs != 1 {
		t.Fatalf("expected 1 indexed batch, got %d", f.addDocs)
	}
}

func TestClient_AddDocumentsFailedTask(t *testing.T) {
	f := newFakeMeili()
	ts := httptest.NewServer(f)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "key", time.Second)

	f.taskStatus.Store("failed")
	err := c.AddDocuments(context.Background(), entityLocations, []any{map[string]any{"id": 1}})
	if err == nil {
		t.Fatal("failed task must surface an error")
	}
}

func TestClient_SearchAllPagination(t *testing.T) {
	// A fake that pages docs 3 at a time until exhausted.
	allDocs := []map[string]any{
		{"id": 1}, {"id": 2}, {"id": 3}, {"id": 4}, {"id": 5},
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/search") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		var req meiliSearchRequest
		json.NewDecoder(r.Body).Decode(&req)
		end := req.Offset + req.Limit
		if end > len(allDocs) {
			end = len(allDocs)
		}
		page := allDocs[req.Offset:end]
		estimate := int64(len(allDocs))
		json.NewEncoder(w).Encode(map[string]any{
			"hits":               page,
			"estimatedTotalHits": estimate,
		})
	})
	ts := httptest.NewServer(handler)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "", time.Second)

	hits, estimate, err := c.SearchAll(context.Background(), entityLocations, "x", nil, nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != len(allDocs) || estimate != int64(len(allDocs)) {
		t.Fatalf("got %d hits, estimate %d", len(hits), estimate)
	}
}

func TestClient_SearchAllRespectsCap(t *testing.T) {
	count := 0
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/search") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		count++
		var req meiliSearchRequest
		json.NewDecoder(r.Body).Decode(&req)
		batch := make([]map[string]any, 0, req.Limit)
		for i := 0; i < req.Limit; i++ {
			batch = append(batch, map[string]any{"id": req.Offset + i})
		}
		json.NewEncoder(w).Encode(map[string]any{"hits": batch, "estimatedTotalHits": 999999})
	})
	ts := httptest.NewServer(handler)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "", time.Second)

	hits, _, err := c.SearchAll(context.Background(), entityLocations, "x", nil, nil, 2500)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 2500 {
		t.Fatalf("cap: got %d hits", len(hits))
	}
}

func TestClient_DoAuthHeader(t *testing.T) {
	gotKey := ""
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotKey = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "available"})
	})
	ts := httptest.NewServer(handler)
	defer ts.Close()
	c := NewMeiliClient(ts.URL, "my-key", time.Second)

	_ = c.Health(context.Background())
	if gotKey != "Bearer my-key" {
		t.Fatalf("auth header wrong: %q", gotKey)
	}
}
