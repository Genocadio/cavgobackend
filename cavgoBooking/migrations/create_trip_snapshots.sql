-- Migration for trip_snapshots table
-- This table stores real-time snapshot of trip booking status including capacity and location-based seat tracking

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

-- Create index on trip_id for fast lookups (one snapshot per trip)
CREATE UNIQUE INDEX IF NOT EXISTS idx_trip_snapshots_trip_id ON trip_snapshots(trip_id);

-- Create index on trip_status for filtering
CREATE INDEX IF NOT EXISTS idx_trip_snapshots_status ON trip_snapshots(trip_status);

-- Create index on last_updated for time-based queries
CREATE INDEX IF NOT EXISTS idx_trip_snapshots_last_updated ON trip_snapshots(last_updated);

-- Example JSONB structure for reference:
-- capacity: {"totalSeats": 30, "availableSeats": 8, "occupiedSeats": 18, "pendingPaymentSeats": 4}
-- locations: [{"locationId": "1", "type": "ORIGIN", "order": 0, "status": "PASSED", "seats": {...}}, ...]
-- summary: {"totalTickets": 22, "paidTickets": 18, "pendingPayments": 4, "completedDropoffs": 18}

COMMENT ON TABLE trip_snapshots IS 'Stores real-time snapshot of trip booking status including capacity and location-based seat tracking';
COMMENT ON COLUMN trip_snapshots.capacity IS 'JSONB: totalSeats, availableSeats, occupiedSeats, pendingPaymentSeats';
COMMENT ON COLUMN trip_snapshots.locations IS 'JSONB array: location-specific booking data with pickup/dropoff counts';
COMMENT ON COLUMN trip_snapshots.summary IS 'JSONB: aggregate statistics (totalTickets, paidTickets, pendingPayments, completedDropoffs)';
