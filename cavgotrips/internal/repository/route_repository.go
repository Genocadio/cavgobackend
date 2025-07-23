package repository

import (
	"cavgotrips/internal/models"
	"errors"
	"fmt"

	"gorm.io/gorm"
)

type routeRepository struct {
	db *gorm.DB
}

func NewRouteRepository(db *gorm.DB) RouteRepository {
	return &routeRepository{db: db}
}

// ValidateLocationExists checks if a location with the given ID exists
func (r *routeRepository) ValidateLocationExists(locationID int64) error {
	var count int64
	if err := r.db.Model(&models.Location{}).Where("id = ?", locationID).Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return fmt.Errorf("location with ID %d does not exist", locationID)
	}
	return nil
}

// ValidateAllLocationsExist validates that origin, destination, and all waypoint locations exist
func (r *routeRepository) ValidateAllLocationsExist(route *models.Route) error {
	// Validate origin location exists
	if err := r.ValidateLocationExists(route.OriginID); err != nil {
		return fmt.Errorf("origin location validation failed: %w", err)
	}

	// Validate destination location exists
	if err := r.ValidateLocationExists(route.DestinationID); err != nil {
		return fmt.Errorf("destination location validation failed: %w", err)
	}

	// Validate all waypoint locations exist
	for i, waypoint := range route.Waypoints {
		if err := r.ValidateLocationExists(waypoint.LocationID); err != nil {
			return fmt.Errorf("waypoint %d location validation failed: %w", i+1, err)
		}
	}

	return nil
}

func (r *routeRepository) CheckUniqueness(route *models.Route) error {
	var count int64

	// Check for route with same origin and destination
	if err := r.db.Model(&models.Route{}).
		Where("origin_id = ? AND destination_id = ? AND id != ?",
			route.OriginID, route.DestinationID, route.ID).
		Count(&count).Error; err != nil {
		return err
	}
	if count > 0 {
		return errors.New("a route with the same origin and destination already exists")
	}

	// Check for route with same name (if provided)
	if route.Name != nil && *route.Name != "" {
		if err := r.db.Model(&models.Route{}).
			Where("name = ? AND id != ?", *route.Name, route.ID).
			Count(&count).Error; err != nil {
			return err
		}
		if count > 0 {
			return errors.New("a route with this name already exists")
		}
	}

	// Check for route with same Google Route ID (if provided)
	if route.GoogleRouteID != nil && *route.GoogleRouteID != "" {
		if err := r.db.Model(&models.Route{}).
			Where("google_route_id = ? AND id != ?", *route.GoogleRouteID, route.ID).
			Count(&count).Error; err != nil {
			return err
		}
		if count > 0 {
			return errors.New("a route with this Google Route ID already exists")
		}
	}

	return nil
}

func (r *routeRepository) Create(route *models.Route) error {
	// First validate all locations exist
	if err := r.ValidateAllLocationsExist(route); err != nil {
		return err
	}

	// Check uniqueness constraints
	if err := r.CheckUniqueness(route); err != nil {
		return err
	}

	return r.db.Transaction(func(tx *gorm.DB) error {
		// Store waypoints before clearing them
		waypoints := route.Waypoints

		// Clear waypoints to prevent GORM from creating them automatically
		route.Waypoints = nil

		// Create route without waypoints
		if err := tx.Create(route).Error; err != nil {
			return err
		}

		// Create waypoints if provided
		for i, waypoint := range waypoints {
			waypoint.ID = 0 // Reset ID to let database auto-generate
			waypoint.RouteID = route.ID
			waypoint.Order = i + 1 // Set order based on arrangement index
			if err := tx.Create(&waypoint).Error; err != nil {
				return err
			}
		}

		return nil
	})
}

func (r *routeRepository) GetAll() ([]models.Route, error) {
	var routes []models.Route
	err := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location").Find(&routes).Error
	return routes, err
}

func (r *routeRepository) GetByID(id int64) (*models.Route, error) {
	var route models.Route
	err := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location").First(&route, id).Error
	if err != nil {
		return nil, err
	}
	return &route, nil
}

func (r *routeRepository) GetByIDWithWaypoints(id int64) (*models.Route, error) {
	var route models.Route
	err := r.db.Preload("Waypoints.Location").First(&route, id).Error
	if err != nil {
		return nil, err
	}
	return &route, nil
}
