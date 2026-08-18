-- The old 6-digit pickup code (generated at accept time) was replaced by the
-- delivery code flow (initiateDelivery → confirmDelivery). This table is unused.
DROP TABLE IF EXISTS pickup_codes;
