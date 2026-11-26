ALTER TABLE IF EXISTS package_assignments
    ADD COLUMN IF NOT EXISTS batch_id UUID,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS objection_deadline TIMESTAMP,
    ADD COLUMN IF NOT EXISTS objected_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS objection_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_package_assignments_driver_assigned_at
    ON package_assignments(driver_id, assigned_at DESC);

CREATE INDEX IF NOT EXISTS idx_package_assignments_batch_id
    ON package_assignments(batch_id);
