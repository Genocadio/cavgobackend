package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

type RouteService struct {
	repo                  repository.RouteRepository
	changeTrackingService *ChangeTrackingService
}

func NewRouteService(repo repository.RouteRepository, changeTrackingService *ChangeTrackingService) *RouteService {
	return &RouteService{
		repo:                  repo,
		changeTrackingService: changeTrackingService,
	}
}

func (s *RouteService) CreateRoute(route *models.Route) error {
	if err := route.Validate(); err != nil {
		return models.NewValidationError(err.Error())
	}

	if err := s.repo.CheckUniqueness(route); err != nil {
		return models.NewConflictError(err.Error())
	}

	err := s.repo.Create(route)
	if err != nil {
		return err
	}

	// Record change for tracking
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("route", route.ID, models.ChangeOperationCreated); err != nil {
			// Don't fail the operation, just log the error
		}
	}

	return nil
}

func (s *RouteService) GetAllRoutes() ([]models.Route, error) {
	return s.repo.GetAll()
}

func (s *RouteService) GetAllRoutesPaginated(limit, offset int) ([]models.Route, int64, error) {
	return s.repo.GetAllPaginated(limit, offset)
}

func (s *RouteService) GetRouteByID(id int64) (*models.Route, error) {
	return s.repo.GetByID(id)
}

func (s *RouteService) GetRouteWithWaypoints(id int64) (*models.Route, error) {
	return s.repo.GetByIDWithWaypoints(id)
}

func (s *RouteService) SearchByOriginDestination(origin, destination string) ([]models.Route, error) {
	return s.repo.SearchByOriginDestination(origin, destination)
}

func (s *RouteService) SearchByOriginDestinationPaginated(origin, destination string, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.SearchByOriginDestinationPaginated(origin, destination, limit, offset)
}

func (s *RouteService) FilterByCityRoute(cityRoute *bool) ([]models.Route, error) {
	return s.repo.FilterByCityRoute(cityRoute)
}

func (s *RouteService) FilterByCityRoutePaginated(cityRoute *bool, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.FilterByCityRoutePaginated(cityRoute, limit, offset)
}

func (s *RouteService) FilterByProvinces(originProvince, destinationProvince string) ([]models.Route, error) {
	return s.repo.FilterByProvinces(originProvince, destinationProvince)
}

func (s *RouteService) FilterByProvincesPaginated(originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.FilterByProvincesPaginated(originProvince, destinationProvince, limit, offset)
}

func (s *RouteService) SearchAndFilter(origin, destination string, cityRoute *bool, originProvince, destinationProvince string) ([]models.Route, error) {
	return s.repo.SearchAndFilter(origin, destination, cityRoute, originProvince, destinationProvince)
}

func (s *RouteService) SearchAndFilterPaginated(origin, destination string, cityRoute *bool, originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.SearchAndFilterPaginated(origin, destination, cityRoute, originProvince, destinationProvince, limit, offset)
}

func (s *RouteService) UpdateRoute(route *models.Route) error {
	if err := route.Validate(); err != nil {
		return models.NewValidationError(err.Error())
	}

	if err := s.repo.CheckUniqueness(route); err != nil {
		return models.NewConflictError(err.Error())
	}

	err := s.repo.Update(route)
	if err != nil {
		return err
	}

	// Record change for tracking
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("route", route.ID, models.ChangeOperationUpdated); err != nil {
			// Don't fail the operation, just log the error
		}
	}

	return nil
}

func (s *RouteService) DeleteRoute(id int64) error {
	err := s.repo.Delete(id)
	if err != nil {
		return err
	}

	// Record change for tracking (deletion)
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("route", id, models.ChangeOperationDeleted); err != nil {
			// Don't fail the operation, just log the error
		}
	}

	return nil
}

func (s *RouteService) GetRoutesByPriceRange(minPrice, maxPrice float64) ([]models.Route, error) {
	return s.repo.GetRoutesByPriceRange(minPrice, maxPrice)
}

func (s *RouteService) GetRoutesByPriceRangePaginated(minPrice, maxPrice float64, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.GetRoutesByPriceRangePaginated(minPrice, maxPrice, limit, offset)
}

func (s *RouteService) GetRoutesByDistanceRange(minDistance, maxDistance int) ([]models.Route, error) {
	return s.repo.GetRoutesByDistanceRange(minDistance, maxDistance)
}

func (s *RouteService) GetRoutesByDistanceRangePaginated(minDistance, maxDistance int, limit, offset int) ([]models.Route, int64, error) {
	return s.repo.GetRoutesByDistanceRangePaginated(minDistance, maxDistance, limit, offset)
}

func (s *RouteService) GetRouteStatistics() (map[string]interface{}, error) {
	return s.repo.GetRouteStatistics()
}
