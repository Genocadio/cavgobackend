package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"fmt"
)

type LocationService struct {
	repo repository.LocationRepository
}

func NewLocationService(repo repository.LocationRepository) *LocationService {
	return &LocationService{repo: repo}
}

// In internal/service/location_service.go

func (s *LocationService) CreateLocation(location *models.Location) error {

	if err := location.Validate(); err != nil {
		return models.NewValidationError(err.Error())
	}

	// Check for duplicate custom name
	if location.CustomName != nil {
		exists, err := s.repo.ExistsByCustomName(*location.CustomName)
		if err != nil {
			return err
		}
		if exists {
			return fmt.Errorf("location with this custom name already exists")
		}
	}

	if location.Code != nil {
		exits, err := s.repo.ExistsByCode(*location.Code)
		if err != nil {
			return err
		}
		if exits {
			return fmt.Errorf("location with this code already exists")
		}
	}

	// Check for duplicate place_id
	if location.PlaceID != nil {
		exists, err := s.repo.ExistsByPlaceID(*location.PlaceID)
		if err != nil {
			return err
		}
		if exists {
			return fmt.Errorf("location with this place_id already exists")
		}
	}

	// Check for duplicate latitude and longitude
	exists, err := s.repo.ExistsByLatLng(location.Latitude, location.Longitude)
	if err != nil {
		return err
	}
	if exists {
		return fmt.Errorf("location with this latitude and longitude already exists")
	}

	return s.repo.Create(location)
}

func (s *LocationService) GetAllLocations() ([]models.Location, error) {
	return s.repo.GetAll()
}

func (s *LocationService) GetLocationByID(id int64) (*models.Location, error) {
	return s.repo.GetByID(id)
}
