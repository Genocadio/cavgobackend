CREATE TABLE IF NOT EXISTS offices (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    contact     VARCHAR(255),
    location_id UUID REFERENCES package_locations(id),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_offices_location ON offices(location_id);

-- Allow standalone location rows (an office location not tied to a package).
ALTER TABLE package_locations ALTER COLUMN package_id DROP NOT NULL;
