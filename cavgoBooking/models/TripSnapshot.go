package models

import (
	"time"
)

// LocationStatus represents the status of a location in a trip
type LocationStatus string

const (
	LocationStatusPassed   LocationStatus = "PASSED"
	LocationStatusCurrent  LocationStatus = "CURRENT"
	LocationStatusUpcoming LocationStatus = "UPCOMING"
)

// LocationType represents the type of location
type LocationType string

const (
	LocationTypeOrigin      LocationType = "ORIGIN"
	LocationTypeWaypoint    LocationType = "WAYPOINT"
	LocationTypeDestination LocationType = "DESTINATION"
)

// TripSnapshot represents the complete snapshot of a trip's booking state
type TripSnapshot struct {
	ID          string             `json:"id" db:"id"`
	TripID      int                `json:"trip_id" db:"trip_id"`
	TripStatus  string             `json:"trip_status" db:"trip_status"`
	LastUpdated time.Time          `json:"last_updated" db:"last_updated"`
	Capacity    SnapshotCapacity   `json:"capacity" gorm:"type:jsonb"`
	Locations   []SnapshotLocation `json:"locations" gorm:"type:jsonb"`
	Summary     SnapshotSummary    `json:"summary" gorm:"type:jsonb"`
	CreatedAt   time.Time          `json:"created_at" db:"created_at"`
	UpdatedAt   time.Time          `json:"updated_at" db:"updated_at"`
}

// SnapshotCapacity represents the capacity information of a trip
type SnapshotCapacity struct {
	TotalSeats          int `json:"totalSeats"`
	AvailableSeats      int `json:"availableSeats"`
	OccupiedSeats       int `json:"occupiedSeats"`
	PendingPaymentSeats int `json:"pendingPaymentSeats"`
}

// SnapshotLocation represents location-specific booking data
type SnapshotLocation struct {
	LocationID string         `json:"locationId"`
	Type       LocationType   `json:"type"`
	Order      int            `json:"order"`
	Status     LocationStatus `json:"status"`
	Seats      LocationSeats  `json:"seats"`
}

// LocationSeats represents seat counts at a specific location
type LocationSeats struct {
	Pickup            int `json:"pickup"`
	Dropoff           int `json:"dropoff"`
	PendingPayment    int `json:"pendingPayment"`
	AvailableFromHere int `json:"availableFromHere"`
}

// SnapshotSummary represents summary statistics of bookings
type SnapshotSummary struct {
	TotalTickets      int `json:"totalTickets"`
	PaidTickets       int `json:"paidTickets"`
	PendingPayments   int `json:"pendingPayments"`
	CompletedDropoffs int `json:"completedDropoffs"`
}

// TripSnapshotPublish represents the snapshot for RabbitMQ publishing (with ISO timestamps)
type TripSnapshotPublish struct {
	TripID      string             `json:"tripId"`
	TripStatus  string             `json:"tripStatus"`
	LastUpdated string             `json:"lastUpdated"` // ISO 8601 format
	Capacity    SnapshotCapacity   `json:"capacity"`
	Locations   []SnapshotLocation `json:"locations"`
	Summary     SnapshotSummary    `json:"summary"`
}
