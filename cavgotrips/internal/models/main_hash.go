package models

import "time"

// MainHash stores merged hash with sorted ID arrays for hash calculation
// Hash is always 64 hex chars regardless of ID count
type MainHash struct {
	ID              int64     `json:"id" gorm:"primaryKey"`
	Hash            string    `json:"hash" gorm:"type:varchar(64);uniqueIndex;not null"` // SHA-256 hash (64 hex chars)
	LocationIDs     []int64   `json:"location_ids" gorm:"type:jsonb;serializer:json"`    // Sorted array of location IDs
	RouteIDs        []int64   `json:"route_ids" gorm:"type:jsonb;serializer:json"`       // Sorted array of route IDs
	IncludedBatches []int64   `json:"included_batches" gorm:"type:jsonb;serializer:json"` // Array of batch IDs included in this merge
	CreatedAt       time.Time `json:"created_at"`
	Type            string    `json:"type" gorm:"type:varchar(20);not null"` // "auto" or "manual"
}


