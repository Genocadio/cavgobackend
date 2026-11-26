CREATE TABLE IF NOT EXISTS office_claims (
    id UUID PRIMARY KEY,
    package_id UUID NOT NULL,
    driver_id BIGINT NOT NULL,
    claimed_by BIGINT NOT NULL,
    office_location_id UUID NOT NULL,
    claimed_at TIMESTAMP NOT NULL,
    dispute_deadline TIMESTAMP NOT NULL,
    disputed_at TIMESTAMP,
    dispute_reason TEXT,
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_office_claims_package_claimed_at
    ON office_claims(package_id, claimed_at DESC);

CREATE INDEX IF NOT EXISTS idx_office_claims_driver_claimed_at
    ON office_claims(driver_id, claimed_at DESC);

CREATE INDEX IF NOT EXISTS idx_office_claims_office_location_id
    ON office_claims(office_location_id);
