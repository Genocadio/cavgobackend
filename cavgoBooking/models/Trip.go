package models

import "time"

// Location represents a location in the trip system
// JSON fields are mapped to struct fields for decoding
// Some fields are nullable, so use pointer types where appropriate

type Location struct {
	ID              int       `json:"id" db:"id"`
	Latitude        float64   `json:"latitude" db:"latitude"`
	Longitude       float64   `json:"longitude" db:"longitude"`
	Price           *float64  `json:"price,omitempty" db:"price"`
	Code            *string   `json:"code,omitempty" db:"code"`
	GooglePlaceName *string   `json:"google_place_name,omitempty" db:"google_place_name"`
	CustomName      *string   `json:"custom_name,omitempty" db:"custom_name"`
	PlaceID         *string   `json:"place_id,omitempty" db:"place_id"`
	CreatedAt       time.Time `json:"created_at" db:"created_at"`
	UpdatedAt       time.Time `json:"updated_at" db:"updated_at"`
}

type TripWaypoint struct {
	ID                int       `json:"id" db:"id"`
	TripID            int       `json:"trip_id" db:"trip_id"`
	LocationID        int       `json:"location_id" db:"location_id"`
	Order             int       `json:"order" db:"order"`
	Price             float64   `json:"price" db:"price"`
	IsPassed          bool      `json:"is_passed" db:"is_passed"`
	IsNext            bool      `json:"is_next" db:"is_next"`
	PassedTimestamp   *int64    `json:"passed_timestamp,omitempty" db:"passed_timestamp"`
	RemainingTime     *int64    `json:"remaining_time,omitempty" db:"remaining_time"`
	RemainingDistance *float64  `json:"remaining_distance,omitempty" db:"remaining_distance"`
	IsCustom          bool      `json:"is_custom" db:"is_custom"`
	CreatedAt         time.Time `json:"created_at" db:"created_at"`
	UpdatedAt         time.Time `json:"updated_at" db:"updated_at"`
	Location          Location  `json:"location" db:"-"`
}

type Route struct {
	ID                       int            `json:"id" db:"id"`
	Name                     *string        `json:"name,omitempty" db:"name"`
	DistanceMeters           *int64         `json:"distance_meters,omitempty" db:"distance_meters"`
	EstimatedDurationSeconds *int64         `json:"estimated_duration_seconds,omitempty" db:"estimated_duration_seconds"`
	GoogleRouteID            *string        `json:"google_route_id,omitempty" db:"google_route_id"`
	OriginID                 int            `json:"origin_id" db:"origin_id"`
	DestinationID            int            `json:"destination_id" db:"destination_id"`
	RoutePrice               float64        `json:"route_price" db:"route_price"`
	CityRoute                bool           `json:"city_route" db:"city_route"`
	CreatedAt                time.Time      `json:"created_at" db:"created_at"`
	UpdatedAt                time.Time      `json:"updated_at" db:"updated_at"`
	Origin                   Location       `json:"origin" db:"-"`
	Destination              Location       `json:"destination" db:"-"`
	Waypoints                *[]interface{} `json:"waypoints" db:"-"`
}

type Trip struct {
	ID                             int            `json:"id" db:"id"`
	RouteID                        int            `json:"route_id" db:"route_id"`
	CarPlate                       string         `json:"car_plate" db:"car_plate"`
	CarCompany                     string         `json:"car_company" db:"car_company"`
	Status                         string         `json:"status" db:"status"`
	DepartureTime                  int64          `json:"departure_time" db:"departure_time"`
	CompletionTime                 *int64         `json:"completion_time,omitempty" db:"completion_time"`
	ConnectionMode                 string         `json:"connection_mode" db:"connection_mode"`
	Notes                          *string        `json:"notes,omitempty" db:"notes"`
	Seats                          int            `json:"seats" db:"seats"`
	RemainingTimeToDestination     *int64         `json:"remaining_time_to_destination,omitempty" db:"remaining_time_to_destination"`
	RemainingDistanceToDestination *float64       `json:"remaining_distance_to_destination,omitempty" db:"remaining_distance_to_destination"`
	IsReversed                     bool           `json:"is_reversed" db:"is_reversed"`
	CurrentSpeed                   *float64       `json:"current_speed,omitempty" db:"current_speed"`
	CurrentLatitude                *float64       `json:"current_latitude,omitempty" db:"current_latitude"`
	CurrentLongitude               *float64       `json:"current_longitude,omitempty" db:"current_longitude"`
	HasCustomWaypoints             bool           `json:"has_custom_waypoints" db:"has_custom_waypoints"`
	CreatedAt                      time.Time      `json:"created_at" db:"created_at"`
	UpdatedAt                      time.Time      `json:"updated_at" db:"updated_at"`
	Route                          Route          `json:"route" db:"-"`
	Waypoints                      []TripWaypoint `json:"waypoints" db:"-"`
}
