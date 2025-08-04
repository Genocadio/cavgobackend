package models

import (
	"fmt"
	"time"
)

// DriverSnapshot stores driver info at trip creation
// Only name and phone are stored
type DriverSnapshot struct {
	Name  string `json:"name"`
	Phone string `json:"phone"`
}

// VehicleSnapshot stores vehicle info at trip creation
// Only selected fields are stored
type Vehicle struct {
	ID           int64          `json:"id"`
	CompanyID    int64          `json:"company_id"`
	CompanyName  string         `json:"company_name"`
	Capacity     int            `json:"capacity"`
	LicensePlate string         `json:"license_plate"`
	Driver       DriverSnapshot `json:"driver"`
}

// Trip represents a snapshot usage of a route
type Trip struct {
	ID                             int64     `json:"id" gorm:"primaryKey"`
	RouteID                        int64     `json:"route_id"`
	VehicleID                      int64     `json:"vehicle_id" gorm:"not null"`
	Vehicle                        Vehicle   `json:"vehicle" gorm:"type:jsonb;serializer:json"`
	Status                         string    `json:"status" gorm:"not null"` // SCHEDULED, IN_PROGRESS, COMPLETED, NOT_COMPLETED
	DepartureTime                  int64     `json:"departure_time" gorm:"not null"`
	CompletionTime                 *int64    `json:"completion_time"`
	ConnectionMode                 string    `json:"connection_mode" gorm:"not null"` // ONLINE, OFFLINE, HYBRID
	Notes                          *string   `json:"notes"`
	Seats                          int       `json:"seats" gorm:"not null"`
	RemainingTimeToDestination     *int64    `json:"remaining_time_to_destination"`
	RemainingDistanceToDestination *float64  `json:"remaining_distance_to_destination"`
	IsReversed                     bool      `json:"is_reversed" gorm:"default:false"`
	CurrentSpeed                   *float64  `json:"current_speed"` // km/h
	CurrentLatitude                *float64  `json:"current_latitude"`
	CurrentLongitude               *float64  `json:"current_longitude"`
	HasCustomWaypoints             bool      `json:"has_custom_waypoints" gorm:"default:false"`
	CreatedAt                      time.Time `json:"created_at"`
	UpdatedAt                      time.Time `json:"updated_at"`

	// Relationships
	Route     Route          `json:"route" gorm:"foreignKey:RouteID"`
	Waypoints []TripWaypoint `json:"waypoints" gorm:"foreignKey:TripID"`
}

// TripWaypoint represents a waypoint in a trip with progress tracking
type TripWaypoint struct {
	ID                int64     `json:"id" gorm:"primaryKey"`
	TripID            int64     `json:"trip_id"`
	LocationID        int64     `json:"location_id"`
	Order             int       `json:"order" gorm:"not null"`
	Price             *float64  `json:"price"`
	IsPassed          bool      `json:"is_passed" gorm:"default:false"`
	IsNext            bool      `json:"is_next" gorm:"default:false"` // true if this is the next waypoint
	PassedTimestamp   *int64    `json:"passed_timestamp"`
	RemainingTime     *int64    `json:"remaining_time"`                 // seconds to reach this waypoint
	RemainingDistance *float64  `json:"remaining_distance"`             // meters to reach this waypoint
	IsCustom          bool      `json:"is_custom" gorm:"default:false"` // true if this waypoint was added custom, not from route
	CreatedAt         time.Time `json:"created_at"`
	UpdatedAt         time.Time `json:"updated_at"`

	// Relationships
	Location Location `json:"location" gorm:"foreignKey:LocationID"`
}

// TripProgressUpdate represents data for updating trip progress
type TripProgressUpdate struct {
	Status                         *string                  `json:"status"`
	RemainingTimeToDestination     *int64                   `json:"remaining_time_to_destination"`
	RemainingDistanceToDestination *float64                 `json:"remaining_distance_to_destination"`
	CurrentSpeed                   *float64                 `json:"current_speed"`
	CurrentLatitude                *float64                 `json:"current_latitude"`
	CurrentLongitude               *float64                 `json:"current_longitude"`
	CompletionTime                 *int64                   `json:"completion_time"`
	PassedWaypointID               *int64                   `json:"passed_waypoint_id"`
	WaypointUpdates                []WaypointProgressUpdate `json:"waypoint_updates"` // Update remaining time/distance for waypoints
}

// WaypointProgressUpdate represents updates to waypoint progress
type WaypointProgressUpdate struct {
	WaypointID        int64    `json:"waypoint_id"`
	RemainingTime     *int64   `json:"remaining_time"`     // seconds to reach this waypoint
	RemainingDistance *float64 `json:"remaining_distance"` // meters to reach this waypoint
}

// CreateTripRequest represents the request to create a trip
type CreateTripRequest struct {
	RouteID         int64                  `json:"route_id"`
	VehicleID       int64                  `json:"vehicle_id"`
	DepartureTime   int64                  `json:"departure_time"`
	ConnectionMode  string                 `json:"connection_mode"`
	Notes           *string                `json:"notes"`
	IsReversed      bool                   `json:"is_reversed"`
	CustomWaypoints []CreateCustomWaypoint `json:"custom_waypoints"` // Optional custom waypoints
}

// Validate checks for required fields and valid values in CreateTripRequest
func (r *CreateTripRequest) Validate() error {
	if r.RouteID <= 0 {
		return NewValidationError("route_id is required and must be greater than 0")
	}
	if r.VehicleID <= 0 {
		return NewValidationError("vehicle_id is required and must be greater than 0")
	}
	if r.DepartureTime <= 0 {
		return NewValidationError("departure_time is required and must be greater than 0")
	}
	if r.ConnectionMode == "" {
		return NewValidationError("connection_mode is required")
	}
	// No seats check here, seats will be set from vehicle

	validConnectionModes := []string{"ONLINE", "OFFLINE", "HYBRID"}
	connectionValid := false
	for _, mode := range validConnectionModes {
		if r.ConnectionMode == mode {
			connectionValid = true
			break
		}
	}
	if !connectionValid {
		return NewValidationError("invalid connection_mode; must be ONLINE, OFFLINE, or HYBRID")
	}

	for i, wp := range r.CustomWaypoints {
		if wp.LocationID <= 0 {
			return NewValidationError(fmt.Sprintf("custom_waypoints[%d]: location_id is required and must be greater than 0", i))
		}
		// Order is allowed to be 0, but not negative
		if wp.Order < 0 {
			return NewValidationError(fmt.Sprintf("custom_waypoints[%d]: order must be 0 or greater", i))
		}
		// Price is optional, but if provided, must be > 0
		if wp.Price != nil && *wp.Price <= 0 {
			return NewValidationError(fmt.Sprintf("custom_waypoints[%d]: price, if provided, must be greater than 0", i))
		}
		// RemainingTime and RemainingDistance are optional, but if provided, must be >= 0
		if wp.RemainingTime != nil && *wp.RemainingTime < 0 {
			return NewValidationError(fmt.Sprintf("custom_waypoints[%d]: remaining_time, if provided, must be >= 0", i))
		}
		if wp.RemainingDistance != nil && *wp.RemainingDistance < 0 {
			return NewValidationError(fmt.Sprintf("custom_waypoints[%d]: remaining_distance, if provided, must be >= 0", i))
		}
	}

	return nil
}

// CreateCustomWaypoint represents custom waypoint data
type CreateCustomWaypoint struct {
	LocationID        int64    `json:"location_id"`
	Order             int      `json:"order"`
	Price             *float64 `json:"price"`
	RemainingTime     *int64   `json:"remaining_time"`     // initial remaining time
	RemainingDistance *float64 `json:"remaining_distance"` // initial remaining distance
}

// Validate validates the trip data
func (t *Trip) Validate() error {
	if t.Seats <= 0 {
		return NewValidationError("seats must be greater than 0")
	}

	if t.DepartureTime <= 0 {
		return NewValidationError("departure time must be a valid timestamp")
	}

	validStatuses := []string{"SCHEDULED", "IN_PROGRESS", "COMPLETED", "NOT_COMPLETED"}
	statusValid := false
	for _, status := range validStatuses {
		if t.Status == status {
			statusValid = true
			break
		}
	}
	if !statusValid {
		return NewValidationError("invalid status")
	}

	validConnectionModes := []string{"ONLINE", "OFFLINE", "HYBRID"}
	connectionValid := false
	for _, mode := range validConnectionModes {
		if t.ConnectionMode == mode {
			connectionValid = true
			break
		}
	}
	if !connectionValid {
		return NewValidationError("invalid connection mode")
	}

	usedLocations := make(map[int64]bool)

	if len(t.Waypoints) > 0 {
		// Collect and sort orders to validate sequence
		orders := make([]int, len(t.Waypoints))
		for i, waypoint := range t.Waypoints {
			orders[i] = waypoint.Order
		}

		// Check if orders start from 0 or 1 and are sequential
		expectedStart := orders[0]
		if expectedStart != 0 && expectedStart != 1 {
			return NewValidationError("waypoint order must start from 0 or 1")
		}

		// Validate sequential ordering
		for i, waypoint := range t.Waypoints {
			expectedOrder := expectedStart + i
			if waypoint.Order != expectedOrder {
				return NewValidationError(fmt.Sprintf("waypoint orders must be sequential starting from %d, expected %d but got %d", expectedStart, expectedOrder, waypoint.Order))
			}

			// For non-custom waypoints, price should be set
			if !waypoint.IsCustom && (waypoint.Price == nil || *waypoint.Price <= 0) {
				return NewValidationError(fmt.Sprintf("waypoint %d must have a valid price", i+1))
			}

			// Check for duplicate locations (only if location ID is non-zero)
			if waypoint.LocationID != 0 {
				if usedLocations[waypoint.LocationID] {
					return NewValidationError(fmt.Sprintf("waypoint %d has duplicate location ID", i+1))
				}
				usedLocations[waypoint.LocationID] = true
			}
		}
	}
	return nil
}

// PageResponse represents a paginated response with SSE subscription UUID
type PageResponse struct {
	Trips   []Trip `json:"trips"`
	Total   int64  `json:"total"`
	Limit   int    `json:"limit"`
	Offset  int    `json:"offset"`
	SSEUUID string `json:"sse_uuid"` // UUID for SSE subscription
}
