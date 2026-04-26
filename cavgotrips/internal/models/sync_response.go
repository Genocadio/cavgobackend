package models

import "time"

type RouteSyncChange struct {
	ID        int64     `json:"id"`
	Operation string    `json:"operation"` // "created", "updated", "deleted"
	Route     *Route    `json:"route,omitempty"`
	ChangedAt time.Time `json:"changed_at"`
}

type LocationSyncChange struct {
	ID        int64     `json:"id"`
	Operation string    `json:"operation"` // "created", "updated", "deleted"
	Location  *Location `json:"location,omitempty"`
	ChangedAt time.Time `json:"changed_at"`
}

// RoutesSyncResponse represents the response for hash-based route sync
type RoutesSyncResponse struct {
	Hash       string            `json:"hash"`              // Current main hash
	Changed    bool              `json:"changed"`           // Whether data has changed since provided hash
	Routes     []Route           `json:"routes"`            // Changed routes (with location_id refs only)
	Changes    []RouteSyncChange `json:"changes,omitempty"` // Detailed changes with operation semantics
	DeletedIDs []int64           `json:"deleted_ids"`       // IDs of deleted routes
	Page       int               `json:"page,omitempty"`
	Limit      int               `json:"limit,omitempty"`
	Total      int64             `json:"total,omitempty"`
	Message    string            `json:"message,omitempty"` // Optional message (e.g., invalid hash)
}

// LocationsSyncResponse represents the response for hash-based location sync
type LocationsSyncResponse struct {
	Hash       string               `json:"hash"`              // Current main hash
	Changed    bool                 `json:"changed"`           // Whether data has changed since provided hash
	Locations  []Location           `json:"locations"`         // Changed locations (full objects)
	Changes    []LocationSyncChange `json:"changes,omitempty"` // Detailed changes with operation semantics
	DeletedIDs []int64              `json:"deleted_ids"`       // IDs of deleted locations
	Page       int                  `json:"page,omitempty"`
	Limit      int                  `json:"limit,omitempty"`
	Total      int64                `json:"total,omitempty"`
	Message    string               `json:"message,omitempty"` // Optional message (e.g., invalid hash)
}
