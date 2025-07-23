package models

import (
	"errors"
	"fmt"
	"time"
)

// Route represents a route with multiple destinations
type Route struct {
	ID                       int64     `json:"id" gorm:"primaryKey"`
	Name                     *string   `json:"name"`
	DistanceMeters           *int      `json:"distance_meters"`
	EstimatedDurationSeconds *int      `json:"estimated_duration_seconds"`
	GoogleRouteID            *string   `json:"google_route_id"`
	OriginID                 int64     `json:"origin_id"`
	DestinationID            int64     `json:"destination_id"`
	RoutePrice               float64   `json:"route_price"`
	CityRoute                bool      `json:"city_route" gorm:"default:false"`
	CreatedAt                time.Time `json:"created_at"`
	UpdatedAt                time.Time `json:"updated_at"`

	// Relationships
	Origin      Location        `json:"origin" gorm:"foreignKey:OriginID"`
	Destination Location        `json:"destination" gorm:"foreignKey:DestinationID"`
	Waypoints   []RouteWaypoint `json:"waypoints" gorm:"foreignKey:RouteID"`
}

func (r *Route) Validate() error {
	// Check required fields
	if r.RoutePrice <= 0 {
		return errors.New("route price must be greater than zero")
	}
	if r.DistanceMeters == nil || *r.DistanceMeters <= 0 {
		return errors.New("distance is required and must be greater than zero")
	}

	// Origin and destination can't be the same (only check if both are non-zero)
	if r.OriginID != 0 && r.DestinationID != 0 && r.OriginID == r.DestinationID {
		return errors.New("origin and destination cannot be the same location")
	}

	// Track used location IDs to prevent duplicates
	usedLocations := make(map[int64]bool)
	if r.OriginID != 0 {
		usedLocations[r.OriginID] = true
	}
	if r.DestinationID != 0 {
		usedLocations[r.DestinationID] = true
	}

	// Validate waypoints if present
	if len(r.Waypoints) > 0 {
		// Collect and sort orders to validate sequence
		orders := make([]int, len(r.Waypoints))
		for i, waypoint := range r.Waypoints {
			orders[i] = waypoint.Order
		}

		// Check if orders start from 0 or 1 and are sequential
		expectedStart := orders[0]
		if expectedStart != 0 && expectedStart != 1 {
			return errors.New("waypoint order must start from 0 or 1")
		}

		// Validate sequential ordering
		for i, waypoint := range r.Waypoints {
			expectedOrder := expectedStart + i
			if waypoint.Order != expectedOrder {
				return fmt.Errorf("waypoint orders must be sequential starting from %d, expected %d but got %d", expectedStart, expectedOrder, waypoint.Order)
			}

			if waypoint.Price <= 0 {
				return fmt.Errorf("waypoint %d must have a valid price", i+1)
			}

			// Check for duplicate locations (only if location ID is non-zero)
			if waypoint.LocationID != 0 {
				if usedLocations[waypoint.LocationID] {
					return fmt.Errorf("waypoint %d has duplicate location ID", i+1)
				}
				usedLocations[waypoint.LocationID] = true
			}
		}
	}

	return nil
}

// RouteWaypoint represents a waypoint in a route with its price
type RouteWaypoint struct {
	ID         int64     `json:"id" gorm:"primaryKey"`
	RouteID    int64     `json:"route_id"`
	LocationID int64     `json:"location_id"`
	Order      int       `json:"order" gorm:"not null"`
	Price      float64   `json:"price" gorm:"not null"`
	CreatedAt  time.Time `json:"created_at"`

	// Relationships
	Location Location `json:"location" gorm:"foreignKey:LocationID"`
}
