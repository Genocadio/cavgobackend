package models

import (
	"time"
)

// TripLog represents a log entry for a trip update
type TripLog struct {
	ID            int64     `json:"id" gorm:"primaryKey"`
	TripID        int64     `json:"trip_id" gorm:"not null;index"`
	TripSnapshot  Trip      `json:"trip_snapshot" gorm:"type:jsonb;serializer:json"`
	UpdateType    string    `json:"update_type" gorm:"not null"` // "created", "updated", "started", "completed", "cancelled", "deleted"
	TripCreatedAt time.Time `json:"trip_created_at" gorm:"not null;index"` // Original trip creation date for cleanup logic
	LoggedAt      time.Time `json:"logged_at" gorm:"not null"`
}

// TripWaypointLog represents a log entry for a waypoint update
type TripWaypointLog struct {
	ID                int64          `json:"id" gorm:"primaryKey"`
	TripLogID         int64          `json:"trip_log_id" gorm:"not null;index"`
	WaypointID        int64          `json:"waypoint_id" gorm:"not null;index"`
	WaypointSnapshot  TripWaypoint   `json:"waypoint_snapshot" gorm:"type:jsonb;serializer:json"`
	UpdateType        string         `json:"update_type" gorm:"not null"` // "created", "updated", "passed"
	LoggedAt          time.Time      `json:"logged_at" gorm:"not null"`
}




