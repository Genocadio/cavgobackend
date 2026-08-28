package search

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"cavgotrips/internal/models"
)

func TestPaginate(t *testing.T) {
	items := []int{1, 2, 3, 4, 5}

	page, total := paginate(items, 1, 2)
	if total != 5 || len(page) != 2 || page[0] != 1 {
		t.Fatalf("first page: len=%d total=%d", len(page), total)
	}

	page, total = paginate(items, 3, 2)
	if total != 5 || len(page) != 1 || page[0] != 5 {
		t.Fatalf("last page: len=%d total=%d", len(page), total)
	}

	page, total = paginate(items, 10, 2)
	if total != 5 || len(page) != 0 {
		t.Fatalf("out-of-range page must be empty: len=%d total=%d", len(page), total)
	}

	// limit <= 0 -> whole set, no crash, no slicing bug.
	page, total = paginate(items, 2, 0)
	if total != 5 || len(page) != 5 {
		t.Fatalf("limit=0: len=%d total=%d", len(page), total)
	}

	// Empty slice must not panic.
	page, total = paginate([]int{}, 1, 20)
	if total != 0 || len(page) != 0 {
		t.Fatalf("empty: len=%d total=%d", len(page), total)
	}
}

func TestBuildTripMeiliRequest_DefaultExcludesCancelledCompleted(t *testing.T) {
	q, attrs, filter := buildTripMeiliRequest(TripFilters{Company: "Volcano"})
	if q != "Volcano" || len(attrs) != 1 || attrs[0] != "company_name" {
		t.Fatalf("request = (%q, %v)", q, attrs)
	}
	if filter == nil || !strings.Contains(*filter, "CANCELLED") || !strings.Contains(*filter, "COMPLETED") {
		t.Fatalf("filter must exclude CANCELLED/COMPLETED: %v", filter)
	}
}

func TestBuildTripMeiliRequest_Filters(t *testing.T) {
	origins := strPtr("kigali")
	city := false
	_, _, filter := buildTripMeiliRequest(TripFilters{Origin: *origins, CityRoute: &city})
	if filter == nil {
		t.Fatal("expected a filter when city_route is set")
	}
	if !strings.Contains(*filter, "route_city_route = false") {
		t.Errorf("city_route filter missing: %s", *filter)
	}
	if !strings.Contains(*filter, " AND ") {
		t.Errorf("filters should be ANDed: %s", *filter)
	}
}

func TestBuildRouteMeiliRequest_SideSelection(t *testing.T) {
	q, attrs, _ := buildRouteMeiliRequest(RouteFilters{Origin: "kigali"})
	if q != "kigali" || !containsString(attrs, "origin_custom_name") {
		t.Fatalf("origin side wrong: %q %v", q, attrs)
	}

	q, attrs, _ = buildRouteMeiliRequest(RouteFilters{Destination: "huye"})
	if q != "huye" || !containsString(attrs, "destination_custom_name") {
		t.Fatalf("destination side wrong: %q %v", q, attrs)
	}

	// Neither side set -> empty query, meaningful attrs.
	q, attrs, _ = buildRouteMeiliRequest(RouteFilters{})
	if q != "" {
		t.Fatalf("expected empty query, got %q", q)
	}
	if len(attrs) != 0 {
		t.Fatalf("expected no attrs, got %v", attrs)
	}
}

func TestFilterRoutes(t *testing.T) {
	custom := "Kigali City"
	routes := []models.Route{
		{ID: 1, CityRoute: true, Origin: models.Location{CustomName: &custom}},
		{ID: 2, CityRoute: false},
	}

	// city_route mask
	f := RouteFilters{CityRoute: boolPtr(true)}
	got := filterRoutes(routes, f)
	if len(got) != 1 || got[0].ID != 1 {
		t.Fatalf("city_route filter: got %d routes", len(got))
	}

	// origin term: only routes whose custom/google name contains the term.
	f = RouteFilters{CityRoute: boolPtr(true), Origin: "kigali"}
	got = filterRoutes(routes, f)
	if len(got) != 1 || got[0].ID != 1 {
		t.Fatalf("origin term filter: got %d routes", len(got))
	}

	f = RouteFilters{Origin: "butare"}
	got = filterRoutes(routes, f)
	if len(got) != 0 {
		t.Fatalf("no route should match 'butare', got %d", len(got))
	}
}

func TestFilterRoutes_Province(t *testing.T) {
	prov := "Kigali"
	routes := []models.Route{{ID: 1, Origin: models.Location{Province: &prov}}}
	got := filterRoutes(routes, RouteFilters{OriginProvince: "kiga"})
	if len(got) != 1 {
		t.Fatalf("province substring should match, got %d", len(got))
	}
	got = filterRoutes(routes, RouteFilters{OriginProvince: "eastern"})
	if len(got) != 0 {
		t.Fatalf("wrong province should not match, got %d", len(got))
	}
}

func TestLocNameMatches(t *testing.T) {
	custom := "Kigali"
	google := "Kigali Rwanda"
	loc := models.Location{CustomName: &custom, GooglePlaceName: &google}
	if !locNameMatches(loc, "kig") {
		t.Error("case-insensitive substring should match")
	}
	if locNameMatches(loc, "musanze") {
		t.Error("unrelated term should not match")
	}
	empty := models.Location{}
	if !locNameMatches(empty, "") {
		t.Error("empty term matches everything")
	}
	if locNameMatches(empty, "x") {
		t.Error("empty location should not match a term")
	}
}

func TestNewRouteSearchDoc_NoRelationsForZeroLocation(t *testing.T) {
	doc := newRouteSearchDoc(models.Route{ID: 5, OriginID: 0, DestinationID: 0})
	if doc.OriginCustomName != nil || doc.DestinationCustomName != nil {
		t.Fatal("denormalized fields must be nil when relations are not loaded")
	}
	if doc.Route.ID != 5 {
		t.Fatal("embedded route must be preserved")
	}
}

func TestNewRouteSearchDoc_DenormalizesOriginDestination(t *testing.T) {
	custom := "Kigali"
	prov := "Kigali"
	route := models.Route{
		ID:          7,
		Origin:      models.Location{ID: 1, CustomName: &custom, Province: &prov},
		Destination: models.Location{ID: 2},
	}
	doc := newRouteSearchDoc(route)
	if doc.OriginCustomName == nil || *doc.OriginCustomName != "Kigali" {
		t.Fatalf("origin custom name not denormalized: %v", doc.OriginCustomName)
	}
	if doc.OriginProvince == nil || *doc.OriginProvince != "Kigali" {
		t.Fatalf("origin province not denormalized: %v", doc.OriginProvince)
	}
	if doc.DestinationCustomName != nil {
		t.Fatal("destination with no name must stay nil")
	}
}

func TestNewTripSearchDoc_RoundTrip(t *testing.T) {
	cn := "Kigali"
	code := "KIG-01"
	trip := models.Trip{
		ID:     11,
		Status: "SCHEDULED",
		Vehicle: models.Vehicle{
			CompanyName: "Volcano",
		},
		Route: models.Route{
			Origin:      models.Location{ID: 1, CustomName: &cn, Code: &code},
			Destination: models.Location{ID: 2},
			CityRoute:   true,
		},
	}
	doc := newTripSearchDoc(trip)
	if doc.CompanyName == nil || *doc.CompanyName != "Volcano" {
		t.Fatalf("company name not denormalized: %v", doc.CompanyName)
	}
	if doc.OriginCustomName == nil || *doc.OriginCustomName != "Kigali" {
		t.Fatalf("origin custom name not denormalized: %v", doc.OriginCustomName)
	}
	if doc.OriginCode == nil || *doc.OriginCode != "KIG-01" {
		t.Fatalf("origin code not denormalized: %v", doc.OriginCode)
	}
	if !doc.RouteCityRoute {
		t.Fatal("route_city_route must be denormalized")
	}
	if doc.ID != 11 {
		t.Fatal("embedded trip must be preserved")
	}

	// JSON round trip must restore the nested route so meili hits stay usable.
	raw, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	var back tripSearchDoc
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.Route.Origin.ID != 1 || *back.Route.Origin.CustomName != "Kigali" {
		t.Fatal("nested origin lost in JSON round trip")
	}
}

func TestIndexConfigs_ValidRankingRules(t *testing.T) {
	for _, cfg := range indexConfigs() {
		if cfg.UID == "" || cfg.PrimaryKey == "" {
			t.Fatalf("index config missing uid/primaryKey: %+v", cfg)
		}
		if len(cfg.Searchable) == 0 {
			t.Fatalf("index %s has no searchable attributes", cfg.UID)
		}
		for _, rule := range cfg.RankingRules {
			// Meilisearch rejects custom ranking rules that don't reference an
			// existing attribute; only standard rules are allowed here.
			switch rule {
			case "words", "typo", "proximity", "attribute", "sort", "exact", "asc", "desc":
			default:
				if strings.HasSuffix(rule, ":asc") || strings.HasSuffix(rule, ":desc") {
					t.Fatalf("index %s uses custom ranking rule %q that may not exist as an attribute", cfg.UID, rule)
				}
			}
		}
	}
}

func containsString(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

func boolPtr(b bool) *bool { return &b }

func strPtr(s string) *string { return &s }

type stubProvider struct {
	name     string
	location bool
	routes   bool
	trips    bool

	// behaviour knobs
	fail      bool
	callCount int
	err       error
}

func (s *stubProvider) SearchLocationsPaginated(_ context.Context, _ string, _, _ int) ([]models.Location, int64, error) {
	s.callCount++
	if s.fail {
		return nil, 0, errors.New("meili is down")
	}
	return []models.Location{{ID: 1}}, 1, nil
}

func (s *stubProvider) SearchRoutesPaginated(_ context.Context, _ RouteFilters, _, _ int) ([]models.Route, int64, error) {
	s.callCount++
	if s.fail {
		return nil, 0, s.err
	}
	return []models.Route{{ID: 1}}, 1, nil
}

func (s *stubProvider) SearchTripsPaginated(_ context.Context, _ TripFilters, _, _ int) ([]models.Trip, int64, error) {
	s.callCount++
	if s.fail {
		return nil, 0, errors.New("meili is down")
	}
	return []models.Trip{{ID: 1}}, 1, nil
}

var _ LocationSearchProvider = (*stubProvider)(nil)
var _ RouteSearchProvider = (*stubProvider)(nil)
var _ TripSearchProvider = (*stubProvider)(nil)

func managerWithStubs(active string, meili, sql LocationSearchProvider) *Manager {
	return &Manager{
		configured:     ProviderAuto,
		active:         active,
		sqlLocations:   sql,
		meiliLocations: meili,
		breaker:        NewCircuitBreaker(5, 15*time.Second),
		fallbackCount:  make(map[string]int64),
	}
}

func TestManager_DisabledUsesSQL(t *testing.T) {
	meili := &stubProvider{name: "meili"}
	sql := &stubProvider{name: "sql"}
	m := managerWithStubs(ProviderSQL, meili, sql)

	_, _, err := m.SearchLocationsPaginated(context.Background(), "kigali", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if meili.callCount != 0 {
		t.Fatal("meili must not be called when provider is sql")
	}
	if sql.callCount != 1 {
		t.Fatal("sql provider must serve requests in sql mode")
	}
}

func TestManager_MeiliFailureFallsBackToSQL(t *testing.T) {
	meili := &stubProvider{name: "meili", fail: true}
	sql := &stubProvider{name: "sql"}
	m := managerWithStubs(ProviderMeili, meili, sql)

	_, _, err := m.SearchLocationsPaginated(context.Background(), "kigali", 1, 20)
	if err != nil {
		t.Fatal("manager must swallow the meili error and return SQL results")
	}
	if meili.callCount != 1 || sql.callCount != 1 {
		t.Fatalf("expected meili once and sql once, got meili=%d sql=%d", meili.callCount, sql.callCount)
	}
	if m.fallbackCount[entityLocations] != 1 {
		t.Fatalf("fallback counter not recorded: %v", m.fallbackCount)
	}
}

func TestManager_MeiliOpensThenBypasses(t *testing.T) {
	meili := &stubProvider{name: "meili", fail: true}
	sql := &stubProvider{name: "sql"}
	m := managerWithStubs(ProviderMeili, meili, sql)
	m.breaker = NewCircuitBreaker(3, time.Minute)

	for i := 0; i < 3; i++ {
		_, _, err := m.SearchLocationsPaginated(context.Background(), "x", 1, 20)
		if err != nil {
			t.Fatalf("iteration %d: %v", i, err)
		}
	}
	if !m.breaker.IsOpen() {
		t.Fatal("breaker should be open after 3 failures")
	}
	if m.fallbackTotal != 3 {
		t.Fatalf("fallback total = %d, want 3", m.fallbackTotal)
	}

	// While open (cooldown not elapsed) meili is bypassed entirely.
	for i := 0; i < 5; i++ {
		_, _, err := m.SearchLocationsPaginated(context.Background(), "x", 1, 20)
		if err != nil {
			t.Fatal(err)
		}
	}
	if meili.callCount != 3 {
		t.Fatalf("meili must be bypassed while open: called %d times", meili.callCount)
	}
	if sql.callCount != 8 {
		t.Fatalf("sql should serve all requests while open: %d", sql.callCount)
	}
}

func TestManager_Status(t *testing.T) {
	sql := &stubProvider{}
	m := managerWithStubs(ProviderSQL, nil, sql)
	status := m.Status()
	if status["provider"] != ProviderSQL || status["enabled"] != false {
		t.Fatalf("status wrong: %v", status)
	}
}
