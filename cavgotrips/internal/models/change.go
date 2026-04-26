package models

import "time"

const (
	ChangeOperationCreated = "created"
	ChangeOperationUpdated = "updated"
	ChangeOperationDeleted = "deleted"
)

// Change represents an individual change record - one row per changed location/route ID
type Change struct {
	ID            int64     `json:"id" gorm:"primaryKey"`
	ChangeBatchID int64     `json:"change_batch_id" gorm:"not null"`
	ChangedType   string    `json:"changed_type" gorm:"type:varchar(20);not null"` // "location" or "route"
	ChangedID     int64     `json:"changed_id" gorm:"not null"`
	IsDeleted     bool      `json:"is_deleted" gorm:"default:false"`
	Operation     string    `json:"operation" gorm:"type:varchar(20);not null;default:'updated'"` // "created", "updated", "deleted"
	CreatedAt     time.Time `json:"created_at"`

	// Relationships
	ChangeBatch ChangeBatch `json:"change_batch" gorm:"foreignKey:ChangeBatchID"`
}
