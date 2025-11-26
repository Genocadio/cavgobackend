package models

import "time"

// ChangeBatch represents a batch of changes that will be merged into main hash
type ChangeBatch struct {
	ID        int64     `json:"id" gorm:"primaryKey"`
	Hash      string    `json:"hash" gorm:"type:varchar(64)"` // SHA-256 hash (64 hex chars)
	Merged    bool      `json:"merged" gorm:"default:false"`
	CreatedAt time.Time `json:"created_at"`
}


