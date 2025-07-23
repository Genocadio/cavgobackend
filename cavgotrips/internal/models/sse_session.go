package models

import (
	"time"
)

// SSESession represents a database entry for SSE session tracking
type SSESession struct {
	ID        int64     `json:"id" gorm:"primaryKey"`
	UUID      string    `json:"uuid" gorm:"uniqueIndex;not null"`
	TripIDs   []int64   `json:"trip_ids" gorm:"type:jsonb;serializer:json"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

// TableName specifies the table name for SSESession
func (SSESession) TableName() string {
	return "sse_sessions"
}
