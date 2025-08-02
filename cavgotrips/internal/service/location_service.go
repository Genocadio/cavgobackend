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

	// Generate location code if not provided
	if location.Code == nil {
		code, err := s.repo.GenerateLocationCode(*location.Province, *location.District)
		if err != nil {
			return err
		}
		location.Code = &code
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

func (s *LocationService) GetAllLocationsPaginated(limit, offset int) ([]models.Location, int64, error) {
	return s.repo.GetAllPaginated(limit, offset)
}

func (s *LocationService) SearchLocations(searchTerm string) ([]models.Location, error) {
	if searchTerm == "" {
		return s.repo.GetAll()
	}
	return s.repo.Search(searchTerm)
}

func (s *LocationService) SearchLocationsPaginated(searchTerm string, limit, offset int) ([]models.Location, int64, error) {
	if searchTerm == "" {
		return s.repo.GetAllPaginated(limit, offset)
	}
	return s.repo.SearchPaginated(searchTerm, limit, offset)
}

func (s *LocationService) GetLocationByID(id int64) (*models.Location, error) {
	return s.repo.GetByID(id)
}

func (s *LocationService) UpdateLocation(id int64, location *models.Location) error {
	// First check if location exists
	if err := s.repo.ValidateExists(id); err != nil {
		return err
	}

	// Get existing location for comparison
	existingLocation, err := s.repo.GetByID(id)
	if err != nil {
		return err
	}

	// Set the ID to ensure we're updating the correct location
	location.ID = id

	// Validate the location data
	if err := location.Validate(); err != nil {
		return models.NewValidationError(err.Error())
	}

	// Check if province or district has changed
	provinceChanged := false
	districtChanged := false
	
	if location.Province != nil && existingLocation.Province != nil {
		provinceChanged = *location.Province != *existingLocation.Province
	} else if location.Province != nil || existingLocation.Province != nil {
		provinceChanged = true
	}
	
	if location.District != nil && existingLocation.District != nil {
		districtChanged = *location.District != *existingLocation.District
	} else if location.District != nil || existingLocation.District != nil {
		districtChanged = true
	}

	// Generate new location code if province or district changed
	if provinceChanged || districtChanged {
		if location.Province == nil || location.District == nil {
			return fmt.Errorf("province and district are required when changing location administrative area")
		}
		
		newCode, err := s.repo.GenerateLocationCode(*location.Province, *location.District)
		if err != nil {
			return err
		}
		location.Code = &newCode
	}

	// Check for duplicate custom name (excluding current location)
	if location.CustomName != nil {
		if existingLocation.CustomName != nil && *existingLocation.CustomName != *location.CustomName {
			exists, err := s.repo.ExistsByCustomName(*location.CustomName)
			if err != nil {
				return err
			}
			if exists {
				return fmt.Errorf("location with this custom name already exists")
			}
		}
	}

	// Check for duplicate place_id (excluding current location)
	if location.PlaceID != nil {
		if existingLocation.PlaceID != nil && *existingLocation.PlaceID != *location.PlaceID {
			exists, err := s.repo.ExistsByPlaceID(*location.PlaceID)
			if err != nil {
				return err
			}
			if exists {
				return fmt.Errorf("location with this place_id already exists")
			}
		}
	}

	// Check for duplicate latitude and longitude (excluding current location)
	if existingLocation.Latitude != location.Latitude || existingLocation.Longitude != location.Longitude {
		exists, err := s.repo.ExistsByLatLng(location.Latitude, location.Longitude)
		if err != nil {
			return err
		}
		if exists {
			return fmt.Errorf("location with this latitude and longitude already exists")
		}
	}

	// Check for duplicate location code (excluding current location)
	if location.Code != nil {
		if existingLocation.Code != nil && *existingLocation.Code != *location.Code {
			exists, err := s.repo.ExistsByCode(*location.Code)
			if err != nil {
				return err
			}
			if exists {
				return fmt.Errorf("location with this code already exists")
			}
		}
	}

	return s.repo.Update(location)
}

func (s *LocationService) DeleteLocation(id int64) error {
	// First check if location exists
	if err := s.repo.ValidateExists(id); err != nil {
		return err
	}

	return s.repo.Delete(id)
}
