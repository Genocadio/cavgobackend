-- Add last_seen_at column to track when a driver was last active.
-- Used by the scheduled OfflineDriverScheduler to mark drivers OFFLINE after 1hr of inactivity.
ALTER TABLE driver_profiles ADD COLUMN last_seen_at TIMESTAMP NULL;
