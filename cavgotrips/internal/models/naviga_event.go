package models

// NavigaTripUpdateEvent mirrors the events published to cavgomqt.trip.updates fanout exchange
type NavigaTripUpdateEvent struct {
	EventType string        `json:"eventType"`
	Trip      NavigaTripDTO `json:"trip"`
	Timestamp EpochTime     `json:"timestamp"`
	Source    string        `json:"source"`
}

type NavigaTripDTO struct {
	ID                 int64                    `json:"id"`
	CarID              string                   `json:"carId"`
	Status             string                   `json:"status"`
	CreatedAt          EpochTime                `json:"createdAt"`
	CompletedAt        *EpochTime               `json:"completedAt"`
	WaypointProgresses []NavigaWaypointProgress `json:"waypointProgresses"`
	CurrentLocation    *NavigaCurrentLocation   `json:"currentLocation"`
}

type NavigaWaypointProgress struct {
	WaypointIndex     int        `json:"waypointIndex"`
	WaypointID        *string    `json:"waypointId"`
	WaypointName      *string    `json:"waypointName"`
	Latitude          float64    `json:"latitude"`
	Longitude         float64    `json:"longitude"`
	State             string     `json:"state"` // APPROACHING | ARRIVED | DONE
	ArrivedAt         *EpochTime `json:"arrivedAt"`
	RemainingDistance float64    `json:"remainingDistance"`
	RemainingTime     float64    `json:"remainingTime"`
}

type NavigaCurrentLocation struct {
	CarID     string    `json:"carId"`
	Latitude  float64   `json:"latitude"`
	Longitude float64   `json:"longitude"`
	Speed     float64   `json:"speed"` // m/s
	Heading   *float64  `json:"heading"`
	Timestamp EpochTime `json:"timestamp"`
}
