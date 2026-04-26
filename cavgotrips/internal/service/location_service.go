package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"log"
	"strings"
)

type LocationService struct {
	repo                  repository.LocationRepository
	changeTrackingService *ChangeTrackingService
}

func NewLocationService(repo repository.LocationRepository, changeTrackingService *ChangeTrackingService) *LocationService {
	return &LocationService{
		repo:                  repo,
		changeTrackingService: changeTrackingService,
	}
}

// In internal/service/location_service.go

func (s *LocationService) CreateLocation(location *models.Location) error {
	log.Printf("DEBUG: Service: Starting location creation")

	if err := location.Validate(); err != nil {
		log.Printf("ERROR: Service: Location validation failed during creation, error: %v", err)
		return models.NewValidationError(err.Error())
	}

	log.Printf("DEBUG: Service: Location validation passed during creation")

	// Generate location code if not provided
	if location.Code == nil {
		log.Printf("DEBUG: Service: Generating location code for province: %s, district: %s", *location.Province, *location.District)
		code, err := s.repo.GenerateLocationCode(*location.Province, *location.District)
		if err != nil {
			log.Printf("ERROR: Service: Failed to generate location code during creation, province: %s, district: %s, error: %v", *location.Province, *location.District, err)
			return models.NewValidationError(err.Error())
		}
		location.Code = &code
		log.Printf("DEBUG: Service: Generated location code: %s", code)
	}

	// Check for duplicate custom name
	if location.CustomName != nil {
		log.Printf("DEBUG: Service: Checking for duplicate custom name: %s", *location.CustomName)
		exists, err := s.repo.ExistsByCustomName(*location.CustomName)
		if err != nil {
			log.Printf("ERROR: Service: Failed to check custom name existence during creation, error: %v", err)
			return err
		}
		if exists {
			log.Printf("ERROR: Service: Custom name already exists during creation: %s", *location.CustomName)
			return models.NewConflictError("location with this custom name already exists")
		}
	}

	if location.Code != nil {
		log.Printf("DEBUG: Service: Checking for duplicate location code: %s", *location.Code)
		exits, err := s.repo.ExistsByCode(*location.Code)
		if err != nil {
			log.Printf("ERROR: Service: Failed to check code existence during creation, error: %v", err)
			return err
		}
		if exits {
			log.Printf("ERROR: Service: Location code already exists during creation: %s", *location.Code)
			return models.NewConflictError("location with this code already exists")
		}
	}

	// Check for duplicate place_id
	if location.PlaceID != nil {
		log.Printf("DEBUG: Service: Checking for duplicate place_id: %s", *location.PlaceID)
		exists, err := s.repo.ExistsByPlaceID(*location.PlaceID)
		if err != nil {
			log.Printf("ERROR: Service: Failed to check place_id existence during creation, error: %v", err)
			return err
		}
		if exists {
			log.Printf("ERROR: Service: Place ID already exists during creation: %s", *location.PlaceID)
			return models.NewConflictError("location with this place_id already exists")
		}
	}

	// Check for duplicate latitude and longitude
	log.Printf("DEBUG: Service: Checking for duplicate coordinates - Lat: %f, Lng: %f", location.Latitude, location.Longitude)
	exists, err := s.repo.ExistsByLatLng(location.Latitude, location.Longitude)
	if err != nil {
		log.Printf("ERROR: Service: Failed to check coordinates existence during creation, error: %v", err)
		return err
	}
	if exists {
		log.Printf("ERROR: Service: Coordinates already exist during creation - Lat: %f, Lng: %f", location.Latitude, location.Longitude)
		return models.NewConflictError("location with this latitude and longitude already exists")
	}

	log.Printf("DEBUG: Service: All validation checks passed during location creation")

	err = s.repo.Create(location)
	if err != nil {
		log.Printf("ERROR: Service: Failed to create location in database, error: %v", err)
		return err
	}

	log.Printf("DEBUG: Service: Location created successfully in database with ID: %d", location.ID)

	// Record change for tracking
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("location", location.ID, models.ChangeOperationCreated); err != nil {
			log.Printf("ERROR: Service: Failed to record location change for ID: %d, error: %v", location.ID, err)
			// Don't fail the operation, just log the error
		}
	}

	return nil
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
	log.Printf("DEBUG: Service: Starting location update for ID: %d", id)

	// First check if location exists
	if err := s.repo.ValidateExists(id); err != nil {
		log.Printf("ERROR: Service: Location does not exist for ID: %d, error: %v", id, err)
		return err
	}

	// Get existing location for comparison
	existingLocation, err := s.repo.GetByID(id)
	if err != nil {
		log.Printf("ERROR: Service: Failed to get existing location for ID: %d, error: %v", id, err)
		return err
	}

	log.Printf("DEBUG: Service: Found existing location - ID: %d, Code: %v, CustomName: %v, PlaceID: %v",
		existingLocation.ID, existingLocation.Code, existingLocation.CustomName, existingLocation.PlaceID)

	// Set the ID to ensure we're updating the correct location
	location.ID = id

	// Ensure province and district are set for validation and code generation
	// If not provided in update request, use existing values
	if location.Province == nil {
		location.Province = existingLocation.Province
		log.Printf("DEBUG: Service: Using existing province: %v", location.Province)
	}
	if location.District == nil {
		location.District = existingLocation.District
		log.Printf("DEBUG: Service: Using existing district: %v", location.District)
	}

	// Validate the location data
	if err := location.Validate(); err != nil {
		log.Printf("ERROR: Service: Location validation failed for ID: %d, error: %v", id, err)
		return models.NewValidationError(err.Error())
	}

	log.Printf("DEBUG: Service: Location validation passed for ID: %d", id)

	// Check if district has changed (case-insensitive comparison)
	districtChanged := false
	if location.District != nil && existingLocation.District != nil {
		districtChanged = strings.ToLower(*location.District) != strings.ToLower(*existingLocation.District)
	} else if location.District != nil || existingLocation.District != nil {
		districtChanged = true
	}

	log.Printf("DEBUG: Service: District change detected - Changed: %v", districtChanged)
	if districtChanged {
		log.Printf("DEBUG: Service: District changed from '%v' to '%v'", existingLocation.District, location.District)
	}

	// Generate location code only if district changed or existing code is null
	shouldGenerateCode := districtChanged || existingLocation.Code == nil

	if shouldGenerateCode {
		log.Printf("DEBUG: Service: Generating location code - District changed: %v, Existing code null: %v", districtChanged, existingLocation.Code == nil)
		log.Printf("DEBUG: Service: Generating new location code for province: %s, district: %s", *location.Province, *location.District)
		newCode, err := s.repo.GenerateLocationCode(*location.Province, *location.District)
		if err != nil {
			log.Printf("ERROR: Service: Failed to generate location code for ID: %d, province: %s, district: %s, error: %v", id, *location.Province, *location.District, err)
			return models.NewValidationError(err.Error())
		}
		location.Code = &newCode
		log.Printf("DEBUG: Service: Generated new location code: %s", newCode)
	} else {
		// Keep existing code
		location.Code = existingLocation.Code
		log.Printf("DEBUG: Service: Keeping existing location code: %s", *location.Code)
	}

	// Check for duplicate custom name (excluding current location)
	if location.CustomName != nil {
		if existingLocation.CustomName != nil && *existingLocation.CustomName != *location.CustomName {
			log.Printf("DEBUG: Service: Checking for duplicate custom name: %s", *location.CustomName)
			exists, err := s.repo.ExistsByCustomName(*location.CustomName)
			if err != nil {
				log.Printf("ERROR: Service: Failed to check custom name existence for ID: %d, error: %v", id, err)
				return err
			}
			if exists {
				log.Printf("ERROR: Service: Custom name already exists: %s", *location.CustomName)
				return models.NewConflictError("location with this custom name already exists")
			}
		}
	}

	// Check for duplicate place_id (excluding current location)
	if location.PlaceID != nil {
		if existingLocation.PlaceID != nil && *existingLocation.PlaceID != *location.PlaceID {
			log.Printf("DEBUG: Service: Checking for duplicate place_id: %s", *location.PlaceID)
			exists, err := s.repo.ExistsByPlaceID(*location.PlaceID)
			if err != nil {
				log.Printf("ERROR: Service: Failed to check place_id existence for ID: %d, error: %v", id, err)
				return err
			}
			if exists {
				log.Printf("ERROR: Service: Place ID already exists: %s", *location.PlaceID)
				return models.NewConflictError("location with this place_id already exists")
			}
		}
	}

	// Check for duplicate latitude and longitude (excluding current location)
	if existingLocation.Latitude != location.Latitude || existingLocation.Longitude != location.Longitude {
		log.Printf("DEBUG: Service: Checking for duplicate coordinates - Lat: %f, Lng: %f", location.Latitude, location.Longitude)
		exists, err := s.repo.ExistsByLatLng(location.Latitude, location.Longitude)
		if err != nil {
			log.Printf("ERROR: Service: Failed to check coordinates existence for ID: %d, error: %v", id, err)
			return err
		}
		if exists {
			log.Printf("ERROR: Service: Coordinates already exist - Lat: %f, Lng: %f", location.Latitude, location.Longitude)
			return models.NewConflictError("location with this latitude and longitude already exists")
		}
	}

	// Check for duplicate location code (excluding current location)
	if location.Code != nil {
		if existingLocation.Code != nil && *existingLocation.Code != *location.Code {
			log.Printf("DEBUG: Service: Checking for duplicate location code: %s", *location.Code)
			exists, err := s.repo.ExistsByCode(*location.Code)
			if err != nil {
				log.Printf("ERROR: Service: Failed to check code existence for ID: %d, error: %v", id, err)
				return err
			}
			if exists {
				log.Printf("ERROR: Service: Location code already exists: %s", *location.Code)
				return models.NewConflictError("location with this code already exists")
			}
		}
	}

	log.Printf("DEBUG: Service: All validation checks passed for location ID: %d", id)

	err = s.repo.Update(location)
	if err != nil {
		log.Printf("ERROR: Service: Failed to update location in database for ID: %d, error: %v", id, err)
		return err
	}

	log.Printf("DEBUG: Service: Location updated successfully in database for ID: %d", id)

	// Record change for tracking
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("location", id, models.ChangeOperationUpdated); err != nil {
			log.Printf("ERROR: Service: Failed to record location change for ID: %d, error: %v", id, err)
			// Don't fail the operation, just log the error
		}
	}

	return nil
}

func (s *LocationService) DeleteLocation(id int64) error {
	// First check if location exists
	if err := s.repo.ValidateExists(id); err != nil {
		return err
	}

	err := s.repo.Delete(id)
	if err != nil {
		return err
	}

	// Record change for tracking (deletion)
	if s.changeTrackingService != nil {
		if err := s.changeTrackingService.RecordChange("location", id, models.ChangeOperationDeleted); err != nil {
			log.Printf("ERROR: Service: Failed to record location deletion for ID: %d, error: %v", id, err)
			// Don't fail the operation, just log the error
		}
	}

	return nil
}
