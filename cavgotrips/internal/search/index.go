package search

import (
	"context"
	"fmt"
	"log"
	"strconv"
	"sync"

	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

const (
	defaultBatchSize = 1000
	// tolerance for boot count comparison (absolute + relative slack) below
	// which the index is considered up to date.
	countTolerance = 0.1
)

// Indexer keeps the Meilisearch indexes in sync with the database. Index
// mutations are serialized through a mutex (boot reindex / manual reindex /
// write-through updates share it).
type Indexer struct {
	client    *MeiliClient
	locs      repository.LocationRepository
	routes    repository.RouteRepository
	trips     repository.TripRepository
	batchSize int
	indexMu   sync.Mutex
}

func NewIndexer(client *MeiliClient, locs repository.LocationRepository, routes repository.RouteRepository, trips repository.TripRepository) *Indexer {
	return &Indexer{client: client, locs: locs, routes: routes, trips: trips, batchSize: defaultBatchSize}
}

// EnsureIndexes creates the indexes (if missing) and applies their settings.
func (in *Indexer) EnsureIndexes(ctx context.Context) error {
	for _, cfg := range indexConfigs() {
		if err := in.client.EnsureIndex(ctx, cfg); err != nil {
			return fmt.Errorf("ensure index %q: %w", cfg.UID, err)
		}
	}
	return nil
}

// CheckAndReindexIfNeeded compares the database row counts with the indexed
// document counts and, when the difference exceeds tolerance, triggers an
// asynchronous reindex of the affected entities.
func (in *Indexer) CheckAndReindexIfNeeded(ctx context.Context) {
	for _, entity := range []string{entityLocations, entityRoutes, entityTrips} {
		expected, err := in.dbCount(ctx, entity)
		if err != nil {
			log.Printf("[search] count check %s: %v", entity, err)
			continue
		}
		actual, err := in.client.Stats(ctx, entity)
		if err != nil {
			log.Printf("[search] stats %s: %v", entity, err)
			continue
		}
		if withinCountTolerance(actual, expected) {
			continue
		}
		log.Printf("[search] index %s out of sync (db=%d index=%d), reindexing", entity, expected, actual)
		if err := in.Reindex(ctx, entity); err != nil {
			log.Printf("[search] reindex %s: %v", entity, err)
		}
	}
}

func withinCountTolerance(actual, expected int64) bool {
	if expected <= 0 {
		return actual == 0
	}
	diff := actual - expected
	if diff < 0 {
		diff = -diff
	}
	return float64(diff) <= float64(expected)*countTolerance
}

func (in *Indexer) dbCount(ctx context.Context, entity string) (int64, error) {
	switch entity {
	case entityLocations:
		all, err := in.locs.GetAll()
		return int64(len(all)), err
	case entityRoutes:
		all, err := in.routes.GetAll()
		return int64(len(all)), err
	case entityTrips:
		all, err := in.trips.GetAll()
		return int64(len(all)), err
	default:
		return 0, fmt.Errorf("unknown entity %q", entity)
	}
}

// Reindex rebuilds one entity's index: it clears the index then re-adds every
// row in batches. It is safe to call concurrently (serialized).
func (in *Indexer) Reindex(ctx context.Context, entity string) error {
	in.indexMu.Lock()
	defer in.indexMu.Unlock()

	switch entity {
	case entityLocations:
		locations, err := in.locs.GetAll()
		if err != nil {
			return err
		}
		docs := make([]any, len(locations))
		for i, loc := range locations {
			docs[i] = locationSearchDoc{Location: loc}
		}
		return in.rebuild(ctx, entityLocations, docs)
	case entityRoutes:
		routes, err := in.routes.GetAll()
		if err != nil {
			return err
		}
		docs := make([]any, len(routes))
		for i, route := range routes {
			docs[i] = newRouteSearchDoc(route)
		}
		return in.rebuild(ctx, entityRoutes, docs)
	case entityTrips:
		trips, err := in.trips.GetAll()
		if err != nil {
			return err
		}
		docs := make([]any, len(trips))
		for i, trip := range trips {
			docs[i] = newTripSearchDoc(trip)
		}
		return in.rebuild(ctx, entityTrips, docs)
	default:
		return fmt.Errorf("unknown entity %q", entity)
	}
}

func (in *Indexer) rebuild(ctx context.Context, uid string, docs []any) error {
	if err := in.client.DeleteAllDocuments(ctx, uid); err != nil {
		return fmt.Errorf("clear %s: %w", uid, err)
	}
	for start := 0; start < len(docs); start += in.batchSize {
		end := start + in.batchSize
		if end > len(docs) {
			end = len(docs)
		}
		if err := in.client.AddDocuments(ctx, uid, docs[start:end]); err != nil {
			return fmt.Errorf("index %s batch [%d,%d): %w", uid, start, end, err)
		}
	}
	log.Printf("[search] reindexed %s with %d documents", uid, len(docs))
	return nil
}

/********************* write-through sync *********************/

func (in *Indexer) IndexLocation(ctx context.Context, loc models.Location) error {
	return in.client.AddDocuments(ctx, entityLocations, []any{locationSearchDoc{Location: loc}})
}

func (in *Indexer) RemoveLocation(ctx context.Context, id int64) error {
	return in.client.DeleteBatch(ctx, entityLocations, []string{strconv.FormatInt(id, 10)})
}

func (in *Indexer) IndexRoute(ctx context.Context, route models.Route) error {
	return in.client.AddDocuments(ctx, entityRoutes, []any{newRouteSearchDoc(route)})
}

func (in *Indexer) RemoveRoute(ctx context.Context, id int64) error {
	return in.client.DeleteBatch(ctx, entityRoutes, []string{strconv.FormatInt(id, 10)})
}

func (in *Indexer) IndexTrip(ctx context.Context, trip models.Trip) error {
	return in.client.AddDocuments(ctx, entityTrips, []any{newTripSearchDoc(trip)})
}

func (in *Indexer) RemoveTrip(ctx context.Context, id int64) error {
	return in.client.DeleteBatch(ctx, entityTrips, []string{strconv.FormatInt(id, 10)})
}
