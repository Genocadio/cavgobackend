package repository

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"cavgoBooking/models"

	"github.com/jmoiron/sqlx"
)

type TripSnapshotRepository interface {
	// CreateSnapshot creates a new trip snapshot
	CreateSnapshot(ctx context.Context, snapshot *models.TripSnapshot) error

	// GetSnapshotByTripID retrieves the snapshot for a specific trip
	GetSnapshotByTripID(ctx context.Context, tripID int) (*models.TripSnapshot, error)

	// GetSnapshotByTripIDForUpdate retrieves snapshot with row-level lock for updates
	GetSnapshotByTripIDForUpdate(ctx context.Context, tx *sqlx.Tx, tripID int) (*models.TripSnapshot, error)

	// UpdateSnapshot updates an existing snapshot
	UpdateSnapshot(ctx context.Context, snapshot *models.TripSnapshot) error

	// UpdateSnapshotInTx updates snapshot within a transaction
	UpdateSnapshotInTx(ctx context.Context, tx *sqlx.Tx, snapshot *models.TripSnapshot) error

	// BeginTransaction starts a new database transaction
	BeginTransaction(ctx context.Context) (*sqlx.Tx, error)

	// EnsureSchema creates the trip_snapshots table and indexes if they don't exist
	EnsureSchema(ctx context.Context) error
}

type tripSnapshotRepository struct {
	db *sqlx.DB
}

func NewTripSnapshotRepository(db *sqlx.DB) TripSnapshotRepository {
	return &tripSnapshotRepository{db: db}
}

func (r *tripSnapshotRepository) CreateSnapshot(ctx context.Context, snapshot *models.TripSnapshot) error {
	fmt.Printf("[TripSnapshotRepository] Creating snapshot: tripId=%d\n", snapshot.TripID)

	capacityJSON, err := json.Marshal(snapshot.Capacity)
	if err != nil {
		return fmt.Errorf("failed to marshal capacity: %w", err)
	}

	locationsJSON, err := json.Marshal(snapshot.Locations)
	if err != nil {
		return fmt.Errorf("failed to marshal locations: %w", err)
	}

	summaryJSON, err := json.Marshal(snapshot.Summary)
	if err != nil {
		return fmt.Errorf("failed to marshal summary: %w", err)
	}

	query := `
		INSERT INTO trip_snapshots (id, trip_id, trip_status, last_updated, capacity, locations, summary, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`

	now := time.Now()
	snapshot.CreatedAt = now
	snapshot.UpdatedAt = now
	snapshot.LastUpdated = now

	_, err = r.db.ExecContext(ctx, query,
		snapshot.ID,
		snapshot.TripID,
		snapshot.TripStatus,
		snapshot.LastUpdated,
		capacityJSON,
		locationsJSON,
		summaryJSON,
		snapshot.CreatedAt,
		snapshot.UpdatedAt,
	)

	if err != nil {
		return fmt.Errorf("failed to create snapshot: %w", err)
	}

	return nil
}

func (r *tripSnapshotRepository) GetSnapshotByTripID(ctx context.Context, tripID int) (*models.TripSnapshot, error) {
	fmt.Printf("[TripSnapshotRepository] Getting snapshot for tripId=%d\n", tripID)

	query := `
		SELECT id, trip_id, trip_status, last_updated, capacity, locations, summary, created_at, updated_at
		FROM trip_snapshots
		WHERE trip_id = $1
	`

	var snapshot models.TripSnapshot
	var capacityJSON, locationsJSON, summaryJSON []byte

	err := r.db.QueryRowContext(ctx, query, tripID).Scan(
		&snapshot.ID,
		&snapshot.TripID,
		&snapshot.TripStatus,
		&snapshot.LastUpdated,
		&capacityJSON,
		&locationsJSON,
		&summaryJSON,
		&snapshot.CreatedAt,
		&snapshot.UpdatedAt,
	)

	if err == sql.ErrNoRows {
		return nil, nil // No snapshot exists yet
	}

	if err != nil {
		return nil, fmt.Errorf("failed to get snapshot: %w", err)
	}

	if err := json.Unmarshal(capacityJSON, &snapshot.Capacity); err != nil {
		return nil, fmt.Errorf("failed to unmarshal capacity: %w", err)
	}

	if err := json.Unmarshal(locationsJSON, &snapshot.Locations); err != nil {
		return nil, fmt.Errorf("failed to unmarshal locations: %w", err)
	}

	if err := json.Unmarshal(summaryJSON, &snapshot.Summary); err != nil {
		return nil, fmt.Errorf("failed to unmarshal summary: %w", err)
	}

	return &snapshot, nil
}

func (r *tripSnapshotRepository) GetSnapshotByTripIDForUpdate(ctx context.Context, tx *sqlx.Tx, tripID int) (*models.TripSnapshot, error) {
	fmt.Printf("[TripSnapshotRepository] Getting snapshot for update: tripId=%d\n", tripID)

	query := `
		SELECT id, trip_id, trip_status, last_updated, capacity, locations, summary, created_at, updated_at
		FROM trip_snapshots
		WHERE trip_id = $1
		FOR UPDATE
	`

	var snapshot models.TripSnapshot
	var capacityJSON, locationsJSON, summaryJSON []byte

	err := tx.QueryRowContext(ctx, query, tripID).Scan(
		&snapshot.ID,
		&snapshot.TripID,
		&snapshot.TripStatus,
		&snapshot.LastUpdated,
		&capacityJSON,
		&locationsJSON,
		&summaryJSON,
		&snapshot.CreatedAt,
		&snapshot.UpdatedAt,
	)

	if err == sql.ErrNoRows {
		return nil, nil // No snapshot exists yet
	}

	if err != nil {
		return nil, fmt.Errorf("failed to get snapshot for update: %w", err)
	}

	if err := json.Unmarshal(capacityJSON, &snapshot.Capacity); err != nil {
		return nil, fmt.Errorf("failed to unmarshal capacity: %w", err)
	}

	if err := json.Unmarshal(locationsJSON, &snapshot.Locations); err != nil {
		return nil, fmt.Errorf("failed to unmarshal locations: %w", err)
	}

	if err := json.Unmarshal(summaryJSON, &snapshot.Summary); err != nil {
		return nil, fmt.Errorf("failed to unmarshal summary: %w", err)
	}

	return &snapshot, nil
}

func (r *tripSnapshotRepository) UpdateSnapshot(ctx context.Context, snapshot *models.TripSnapshot) error {
	fmt.Printf("[TripSnapshotRepository] Updating snapshot: tripId=%d\n", snapshot.TripID)

	capacityJSON, err := json.Marshal(snapshot.Capacity)
	if err != nil {
		return fmt.Errorf("failed to marshal capacity: %w", err)
	}

	locationsJSON, err := json.Marshal(snapshot.Locations)
	if err != nil {
		return fmt.Errorf("failed to marshal locations: %w", err)
	}

	summaryJSON, err := json.Marshal(snapshot.Summary)
	if err != nil {
		return fmt.Errorf("failed to marshal summary: %w", err)
	}

	query := `
		UPDATE trip_snapshots
		SET trip_status = $1, last_updated = $2, capacity = $3, locations = $4, summary = $5, updated_at = $6
		WHERE trip_id = $7
	`

	snapshot.UpdatedAt = time.Now()
	snapshot.LastUpdated = snapshot.UpdatedAt

	_, err = r.db.ExecContext(ctx, query,
		snapshot.TripStatus,
		snapshot.LastUpdated,
		capacityJSON,
		locationsJSON,
		summaryJSON,
		snapshot.UpdatedAt,
		snapshot.TripID,
	)

	if err != nil {
		return fmt.Errorf("failed to update snapshot: %w", err)
	}

	return nil
}

func (r *tripSnapshotRepository) UpdateSnapshotInTx(ctx context.Context, tx *sqlx.Tx, snapshot *models.TripSnapshot) error {
	fmt.Printf("[TripSnapshotRepository] Updating snapshot in transaction: tripId=%d\n", snapshot.TripID)

	capacityJSON, err := json.Marshal(snapshot.Capacity)
	if err != nil {
		return fmt.Errorf("failed to marshal capacity: %w", err)
	}

	locationsJSON, err := json.Marshal(snapshot.Locations)
	if err != nil {
		return fmt.Errorf("failed to marshal locations: %w", err)
	}

	summaryJSON, err := json.Marshal(snapshot.Summary)
	if err != nil {
		return fmt.Errorf("failed to marshal summary: %w", err)
	}

	query := `
		UPDATE trip_snapshots
		SET trip_status = $1, last_updated = $2, capacity = $3, locations = $4, summary = $5, updated_at = $6
		WHERE trip_id = $7
	`

	snapshot.UpdatedAt = time.Now()
	snapshot.LastUpdated = snapshot.UpdatedAt

	_, err = tx.ExecContext(ctx, query,
		snapshot.TripStatus,
		snapshot.LastUpdated,
		capacityJSON,
		locationsJSON,
		summaryJSON,
		snapshot.UpdatedAt,
		snapshot.TripID,
	)

	if err != nil {
		return fmt.Errorf("failed to update snapshot in transaction: %w", err)
	}

	return nil
}

func (r *tripSnapshotRepository) BeginTransaction(ctx context.Context) (*sqlx.Tx, error) {
	return r.db.BeginTxx(ctx, nil)
}

func (r *tripSnapshotRepository) EnsureSchema(ctx context.Context) error {
	// Create table if not exists
	createTable := `
		CREATE TABLE IF NOT EXISTS trip_snapshots (
			id VARCHAR(255) PRIMARY KEY,
			trip_id INTEGER NOT NULL,
			trip_status VARCHAR(50) NOT NULL,
			last_updated TIMESTAMP NOT NULL,
			capacity JSONB NOT NULL,
			locations JSONB NOT NULL,
			summary JSONB NOT NULL,
			created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
		);
	`
	if _, err := r.db.ExecContext(ctx, createTable); err != nil {
		return fmt.Errorf("failed to create trip_snapshots table: %w", err)
	}

	// Unique index on trip_id ensures single snapshot per trip
	uniqueIndex := `
		CREATE UNIQUE INDEX IF NOT EXISTS idx_trip_snapshots_trip_id ON trip_snapshots(trip_id);
	`
	if _, err := r.db.ExecContext(ctx, uniqueIndex); err != nil {
		return fmt.Errorf("failed to create unique index on trip_id: %w", err)
	}

	// Optional indexes for status and last_updated
	statusIndex := `
		CREATE INDEX IF NOT EXISTS idx_trip_snapshots_status ON trip_snapshots(trip_status);
	`
	if _, err := r.db.ExecContext(ctx, statusIndex); err != nil {
		return fmt.Errorf("failed to create status index: %w", err)
	}

	updatedIndex := `
		CREATE INDEX IF NOT EXISTS idx_trip_snapshots_last_updated ON trip_snapshots(last_updated);
	`
	if _, err := r.db.ExecContext(ctx, updatedIndex); err != nil {
		return fmt.Errorf("failed to create last_updated index: %w", err)
	}

	fmt.Printf("[TripSnapshotRepository] Schema ensured (table and indexes present)\n")
	return nil
}
