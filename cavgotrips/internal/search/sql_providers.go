package search

import (
	"context"

	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

// sqlLocationProvider is the fallback (and default) locations provider backed
// by the GORM repository.
type sqlLocationProvider struct {
	repo repository.LocationRepository
}

func (p *sqlLocationProvider) SearchLocationsPaginated(ctx context.Context, searchTerm string, page, limit int) ([]models.Location, int64, error) {
	if searchTerm == "" {
		return p.repo.GetAllPaginated(limit, (page-1)*limit)
	}
	return p.repo.SearchPaginated(searchTerm, limit, (page-1)*limit)
}

// sqlRouteProvider is the fallback routes provider backed by the GORM
// repository used by GET /routes.
type sqlRouteProvider struct {
	repo repository.RouteRepository
}

func (p *sqlRouteProvider) SearchRoutesPaginated(ctx context.Context, filters RouteFilters, page, limit int) ([]models.Route, int64, error) {
	return p.repo.SearchAndFilterPaginated(
		filters.Origin,
		filters.Destination,
		filters.CityRoute,
		filters.OriginProvince,
		filters.DestinationProvince,
		limit,
		(page-1)*limit,
	)
}

// sqlTripProvider is the fallback trips provider backed by the GORM
// repository used by GET /trips.
type sqlTripProvider struct {
	repo repository.TripRepository
}

func (p *sqlTripProvider) SearchTripsPaginated(ctx context.Context, filters TripFilters, page, limit int) ([]models.Trip, int64, error) {
	if filters.CityRoute != nil {
		return p.repo.GetTripsByFiltersWithCityRoute(filters.Origin, filters.Destination, filters.Company, *filters.CityRoute, limit, (page-1)*limit)
	}
	return p.repo.GetTripsByFiltersPaginated(filters.Origin, filters.Destination, filters.Company, limit, (page-1)*limit)
}
