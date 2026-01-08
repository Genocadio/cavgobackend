package models

// TripEventMessage is used for RabbitMQ trip events (created/updated/started/completed/TRIP_CANCELLED)
type TripEventMessage struct {
	Event string `json:"event"` // "created", "updated", "started", "completed", or "TRIP_CANCELLED"
	Data  Trip   `json:"data"`
}

// MQTTTripEventMessage is used for RabbitMQ trip events from MQTT service
// This matches the Java TripEventMessage structure
type MQTTTripEventMessage struct {
	Event string `json:"event"` // TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED, TRIP_UPDATED
	Data  Trip   `json:"data"`  // Trip object with updated data
}

// TripSnapshotCapacity represents the capacity information from booking service
type TripSnapshotCapacity struct {
	TotalSeats          int `json:"totalSeats"`
	AvailableSeats      int `json:"availableSeats"`
	OccupiedSeats       int `json:"occupiedSeats"`
	PendingPaymentSeats int `json:"pendingPaymentSeats"`
}

// TripSnapshotSeats represents seat information per location
type TripSnapshotSeats struct {
	Pickup            int `json:"pickup"`
	Dropoff           int `json:"dropoff"`
	PendingPayment    int `json:"pendingPayment"`
	AvailableFromHere int `json:"availableFromHere"`
}

// TripSnapshotLocation represents location info in the snapshot
type TripSnapshotLocation struct {
	LocationID string            `json:"locationId"`
	Type       string            `json:"type"` // ORIGIN, WAYPOINT, DESTINATION
	Order      int               `json:"order"`
	Status     string            `json:"status"` // UPCOMING, CURRENT, PASSED
	Seats      TripSnapshotSeats `json:"seats"`
}

// TripSnapshotSummary represents summary info from snapshot
type TripSnapshotSummary struct {
	TotalTickets      int `json:"totalTickets"`
	PaidTickets       int `json:"paidTickets"`
	PendingPayments   int `json:"pendingPayments"`
	CompletedDropoffs int `json:"completedDropoffs"`
}

// TripSnapshot represents the full trip snapshot message from booking service
type TripSnapshot struct {
	TripID      string                 `json:"tripId"`
	TripStatus  string                 `json:"tripStatus"`
	LastUpdated string                 `json:"lastUpdated"`
	Capacity    TripSnapshotCapacity   `json:"capacity"`
	Locations   []TripSnapshotLocation `json:"locations"`
	Summary     TripSnapshotSummary    `json:"summary"`
}
