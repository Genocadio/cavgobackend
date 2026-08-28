package search

import (
	"context"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

const (
	// Provider mode strings for SEARCH_PROVIDER.
	ProviderSQL   = "sql"
	ProviderAuto  = "auto"
	ProviderMeili = "meili"

	meiliBootTimeout = 5 * time.Second
)

// Manager selects the active search provider (Meilisearch or SQL), falls back
// transparently to SQL on error, and coordinates indexing.
type Manager struct {
	configured string
	active     string // "sql" or "meili"

	sqlLocations LocationSearchProvider
	sqlRoutes    RouteSearchProvider
	sqlTrips     TripSearchProvider

	meiliLocations LocationSearchProvider
	meiliRoutes    RouteSearchProvider
	meiliTrips     TripSearchProvider

	breaker *CircuitBreaker
	indexer *Indexer

	statsMu       sync.Mutex
	fallbackCount map[string]int64
	fallbackTotal int64
}

// NewManager builds a search manager and returns the active provider name.
// opts.NewIndexer makes reindex/write-through available even in SQL-only mode
// (indexes are updated but searches never hit Meilisearch).
//
//   - SEARCH_PROVIDER=sql:        always SQL, Meilisearch ignored.
//   - SEARCH_PROVIDER=meili:      must be reachable at boot or an error is
//     returned (fails the service startup).
//   - SEARCH_PROVIDER=auto:       Meilisearch is used when reachable and
//     healthy, otherwise SQL.
func NewManager(searchProvider, meiliURL, meiliAPIKey string, locs repository.LocationRepository, routes repository.RouteRepository, trips repository.TripRepository, newIndexer func(*MeiliClient) *Indexer) (*Manager, error) {
	m := &Manager{
		configured:    searchProvider,
		sqlLocations:  &sqlLocationProvider{repo: locs},
		sqlRoutes:     &sqlRouteProvider{repo: routes},
		sqlTrips:      &sqlTripProvider{repo: trips},
		fallbackCount: make(map[string]int64),
		breaker:       NewCircuitBreaker(5, 15*time.Second),
		active:        ProviderSQL,
	}

	switch searchProvider {
	case ProviderMeili, ProviderAuto:
	default:
		m.configured = ProviderSQL
	}

	if meiliURL != "" {
		client := NewMeiliClient(meiliURL, meiliAPIKey, meiliBootTimeout)
		m.indexer = newIndexer(client)

		ctx, cancel := context.WithTimeout(context.Background(), meiliBootTimeout)
		err := client.Health(ctx)
		cancel()
		if err != nil {
			if searchProvider == ProviderMeili {
				return nil, fmt.Errorf("SEARCH_PROVIDER=meili but Meilisearch is unreachable at %s: %w", meiliURL, err)
			}
			log.Printf("[search] Meilisearch unreachable at %s, falling back to SQL: %v", meiliURL, err)
			m.indexer = nil
			return m, nil
		}

		ctx, cancel = context.WithTimeout(context.Background(), meiliBootTimeout)
		err = m.indexer.EnsureIndexes(ctx)
		cancel()
		if err != nil {
			if searchProvider == ProviderMeili {
				return nil, fmt.Errorf("failed to initialize Meilisearch indexes: %w", err)
			}
			log.Printf("[search] failed to initialize Meilisearch indexes, falling back to SQL: %v", err)
			m.indexer = nil
			return m, nil
		}

		m.meiliLocations = &meiliLocationProvider{client: client}
		m.meiliRoutes = &meiliRouteProvider{client: client}
		m.meiliTrips = &meiliTripProvider{client: client}
		m.active = ProviderMeili
		log.Printf("[search] Meilisearch active at %s", meiliURL)

		ctx, cancel = context.WithTimeout(context.Background(), 30*time.Second)
		m.indexer.CheckAndReindexIfNeeded(ctx)
		cancel()
	}
	log.Printf("[search] active provider: %s", m.active)
	return m, nil
}

// ActiveProvider returns the provider currently serving search requests.
func (m *Manager) ActiveProvider() string {
	if m == nil {
		return ProviderSQL
	}
	return m.active
}

// Enabled reports whether Meilisearch is the active provider.
func (m *Manager) Enabled() bool {
	return m != nil && m.active == ProviderMeili
}

/********************* search entry points *********************/

func (m *Manager) SearchLocationsPaginated(ctx context.Context, term string, page, limit int) ([]models.Location, int64, error) {
	if !m.Enabled() {
		return m.sqlLocations.SearchLocationsPaginated(ctx, term, page, limit)
	}
	if m.breaker.ShouldBypass() {
		m.recordFallback(entityLocations)
		return m.sqlLocations.SearchLocationsPaginated(ctx, term, page, limit)
	}
	res, total, err := m.meiliLocations.SearchLocationsPaginated(ctx, term, page, limit)
	if err != nil {
		m.breaker.Failure()
		m.recordFallback(entityLocations)
		return m.sqlLocations.SearchLocationsPaginated(ctx, term, page, limit)
	}
	m.breaker.Success()
	return res, total, nil
}

func (m *Manager) SearchRoutesPaginated(ctx context.Context, filters RouteFilters, page, limit int) ([]models.Route, int64, error) {
	if !m.Enabled() {
		return m.sqlRoutes.SearchRoutesPaginated(ctx, filters, page, limit)
	}
	if m.breaker.ShouldBypass() {
		m.recordFallback(entityRoutes)
		return m.sqlRoutes.SearchRoutesPaginated(ctx, filters, page, limit)
	}
	res, total, err := m.meiliRoutes.SearchRoutesPaginated(ctx, filters, page, limit)
	if err != nil {
		m.breaker.Failure()
		m.recordFallback(entityRoutes)
		return m.sqlRoutes.SearchRoutesPaginated(ctx, filters, page, limit)
	}
	m.breaker.Success()
	return res, total, nil
}

func (m *Manager) SearchTripsPaginated(ctx context.Context, filters TripFilters, page, limit int) ([]models.Trip, int64, error) {
	if !m.Enabled() {
		return m.sqlTrips.SearchTripsPaginated(ctx, filters, page, limit)
	}
	if m.breaker.ShouldBypass() {
		m.recordFallback(entityTrips)
		return m.sqlTrips.SearchTripsPaginated(ctx, filters, page, limit)
	}
	res, total, err := m.meiliTrips.SearchTripsPaginated(ctx, filters, page, limit)
	if err != nil {
		m.breaker.Failure()
		m.recordFallback(entityTrips)
		return m.sqlTrips.SearchTripsPaginated(ctx, filters, page, limit)
	}
	m.breaker.Success()
	return res, total, nil
}

/********************* indexing (write-through + manual) *********************/

// Reindex rebuilds the given entity. all rebuilds every entity.
func (m *Manager) Reindex(ctx context.Context, entity string) error {
	if m.indexer == nil {
		return errors.New("Meilisearch is not configured")
	}
	switch entity {
	case entityLocations, entityRoutes, entityTrips:
		return m.indexer.Reindex(ctx, entity)
	case "all":
		var firstErr error
		for _, e := range []string{entityLocations, entityRoutes, entityTrips} {
			if err := m.indexer.Reindex(ctx, e); err != nil && firstErr == nil {
				firstErr = err
			}
		}
		return firstErr
	default:
		return fmt.Errorf("unknown entity %q", entity)
	}
}

func (m *Manager) SyncLocation(loc models.Location) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.IndexLocation(context.Background(), loc); err != nil {
			log.Printf("[search] sync location %d: %v", loc.ID, err)
		}
	}()
}

func (m *Manager) RemoveLocation(id int64) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.RemoveLocation(context.Background(), id); err != nil {
			log.Printf("[search] remove location %d: %v", id, err)
		}
	}()
}

func (m *Manager) SyncRoute(route models.Route) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.IndexRoute(context.Background(), route); err != nil {
			log.Printf("[search] sync route %d: %v", route.ID, err)
		}
	}()
}

func (m *Manager) RemoveRoute(id int64) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.RemoveRoute(context.Background(), id); err != nil {
			log.Printf("[search] remove route %d: %v", id, err)
		}
	}()
}

func (m *Manager) SyncTrip(trip models.Trip) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.IndexTrip(context.Background(), trip); err != nil {
			log.Printf("[search] sync trip %d: %v", trip.ID, err)
		}
	}()
}

func (m *Manager) RemoveTrip(id int64) {
	if !m.Enabled() || m.indexer == nil {
		return
	}
	go func() {
		if err := m.indexer.RemoveTrip(context.Background(), id); err != nil {
			log.Printf("[search] remove trip %d: %v", id, err)
		}
	}()
}

/********************* observability *********************/

func (m *Manager) recordFallback(entity string) {
	m.statsMu.Lock()
	defer m.statsMu.Unlock()
	m.fallbackCount[entity]++
	m.fallbackTotal++
}

// Status returns observability info for the health/status endpoints.
func (m *Manager) Status() map[string]any {
	m.statsMu.Lock()
	defer m.statsMu.Unlock()
	failures, open := m.breaker.State()
	return map[string]any{
		"configured": m.configured,
		"provider":   m.active,
		"enabled":    m.active == ProviderMeili,
		"circuit_breaker": map[string]any{
			"open":      open,
			"failures":  failures,
			"half_open": m.breaker.IsHalfOpen(),
		},
		"fallbacks":    m.fallbackTotal,
		"fallbacks_by": cloneMap(m.fallbackCount),
	}
}

func cloneMap(in map[string]int64) map[string]int64 {
	out := make(map[string]int64, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}
