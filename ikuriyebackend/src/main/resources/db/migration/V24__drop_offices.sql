-- Offices are no longer managed in this service. Company / office ids on
-- worker_profiles, driver_profiles, packages, transfers and
-- package_locations are now plain, FK-free UUID data (incoming references),
-- not database-linked rows. Drop the offices table and its index, and rename
-- package_locations.office_id to office_location_id to reflect that this is a
-- plain (non-referenced) office location identifier.
DROP TABLE IF EXISTS offices;

DROP INDEX IF EXISTS idx_offices_location;

ALTER TABLE package_locations RENAME COLUMN office_id TO office_location_id;

-- worker_profiles.company_id was previously required and linked to offices.
-- It is now plain data and no longer populated, so it must accept NULL.
ALTER TABLE worker_profiles ALTER COLUMN company_id DROP NOT NULL;
