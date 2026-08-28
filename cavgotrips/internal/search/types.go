package search

import (
	"context"

	"cavgotrips/internal/models"
)

// Providers contract: search provider interface for locations, routes and trips.
type SearchProvider interface {
	SearchLocationsPaginated(ctx context.Context, searchTerm string, page, limit int) ([]models.Location, int64, error)
	SearchRoutesPaginated(ctx context.Context, filters RouteFilters, page, limit int) ([]models.Route, int64, error)
	SearchTripsPaginated(ctx context.Context, filters TripFilters, page, limit int) ([]models.Trip, int64, error)
}

type LocationSearchProvider interface {
	SearchLocationsPaginated(ctx context.Context, searchTerm string, page, limit int) ([]models.Location, int64, error)
}

type RouteSearchProvider interface {
	SearchRoutesPaginated(ctx context.Context, filters RouteFilters, page, limit int) ([]models.Route, int64, error)
}

type TripSearchProvider interface {
	SearchTripsPaginated(ctx context.Context, filters TripFilters, page, limit int) ([]models.Trip, int64, error)
}

// RouteFilters matches the query parameters of GET /routes.
type RouteFilters struct {
	Origin              string
	Destination         string
	CityRoute           *bool
	OriginProvince      string
	DestinationProvince string
}

// TripFilters matches the query parameters of GET /trips.
type TripFilters struct {
	Origin      string
	Destination string
	Company     string
	CityRoute   *bool
}

const (
	// maxPostFilterFetch caps how many candidates a post-filtered search
	// may pull from Meilisearch before applying in-memory filters.
	maxPostFilterFetch = 10000
)

const (
	entityLocations = "locations"
	entityRoutes    = "routes"
	entityTrips     = "trips"
)
