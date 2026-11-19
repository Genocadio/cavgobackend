-- Per-driver trip/company context for packages.
--
-- `packages.trip_id` and `packages.company_id` describe only the CURRENT carrier.
-- A package can be handed between many drivers, so the full history lives in
-- `package_carrier_legs` — one immutable row per driver that handled the package.
--
-- Company IDs live in two spaces:
--   * company_id       UUID  — cavgomain office location id (existing package semantics)
--   * company_ref_id   BIGINT — company id from the trip service (cavgo_trips.company_id)
-- Both are stored so admin can join on either without lossy conversion.

ALTER TABLE packages
    ADD COLUMN company_ref_id BIGINT NULL,
    ADD COLUMN package_origin VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

CREATE TABLE IF NOT EXISTS package_carrier_legs (
    id             UUID PRIMARY KEY,
    package_id     UUID NOT NULL,
    driver_id      BIGINT NOT NULL,
    trip_id        BIGINT NULL,
    company_id     UUID NULL,
    company_ref_id BIGINT NULL,
    vehicle_id     BIGINT NULL,
    vehicle_json   JSONB NULL,
    custodian_role VARCHAR(32) NOT NULL,
    from_status    VARCHAR(32) NULL,
    to_status      VARCHAR(32) NULL,
    linked_at      TIMESTAMP NOT NULL,
    created_at     TIMESTAMP NOT NULL
);

-- Reporting/filter indexes for "packages with company data" and per-driver history.
CREATE INDEX IF NOT EXISTS idx_packages_company_ref_updated ON packages(company_ref_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_packages_trip_id ON packages(trip_id);
CREATE INDEX IF NOT EXISTS idx_packages_company_status_created ON packages(company_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_carrier_legs_package ON package_carrier_legs(package_id, linked_at);
CREATE INDEX IF NOT EXISTS idx_carrier_legs_trip ON package_carrier_legs(trip_id);
CREATE INDEX IF NOT EXISTS idx_carrier_legs_company_ref ON package_carrier_legs(company_ref_id);
CREATE INDEX IF NOT EXISTS idx_carrier_legs_driver ON package_carrier_legs(driver_id);
