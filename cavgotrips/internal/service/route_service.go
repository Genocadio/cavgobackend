package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

type RouteService struct {
	repo repository.RouteRepository
}

func NewRouteService(repo repository.RouteRepository) *RouteService {
	return &RouteService{repo: repo}
}

func (s *RouteService) CreateRoute(route *models.Route) error {
	if err := route.Validate(); err != nil {
		return models.NewValidationError(err.Error())
	}

	if err := s.repo.CheckUniqueness(route); err != nil {
		return models.NewConflictError(err.Error())
	}

	return s.repo.Create(route)
}

func (s *RouteService) GetAllRoutes() ([]models.Route, error) {
	return s.repo.GetAll()
}

func (s *RouteService) GetRouteByID(id int64) (*models.Route, error) {
	return s.repo.GetByID(id)
}

func (s *RouteService) GetRouteWithWaypoints(id int64) (*models.Route, error) {
	return s.repo.GetByIDWithWaypoints(id)
}
