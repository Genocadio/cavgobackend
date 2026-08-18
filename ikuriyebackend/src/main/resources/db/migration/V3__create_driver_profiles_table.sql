CREATE TABLE IF NOT EXISTS driver_profiles (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL UNIQUE REFERENCES users(id),
    company_id       UUID,
    vehicle_id       UUID,
    employment_type  VARCHAR(20) NOT NULL DEFAULT 'INDEPENDENT',
    driver_type      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    status           VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_driver_profiles_user_id ON driver_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_driver_profiles_company_id ON driver_profiles(company_id);
