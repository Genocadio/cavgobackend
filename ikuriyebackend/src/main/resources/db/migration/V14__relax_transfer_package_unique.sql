-- A package may have only ONE OPEN transfer at a time, but it can appear in many
-- completed/cancelled transfers over time (historical traceability).
-- The old constraint (UNIQUE on package_id alone) wrongly limited a package to a
-- single transfer link for its whole lifetime.
ALTER TABLE transfer_packages DROP CONSTRAINT IF EXISTS uq_transfer_package;
ALTER TABLE transfer_packages ADD CONSTRAINT uq_transfer_package UNIQUE (transfer_id, package_id);
