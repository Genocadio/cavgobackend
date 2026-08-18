CREATE TABLE IF NOT EXISTS delivery_codes (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL UNIQUE REFERENCES packages(id),
    code_hash  VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP,
    used_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_delivery_codes_package ON delivery_codes(package_id);
