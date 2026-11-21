-- Proof-of-delivery photos and post-delivery driver ratings.
--
-- Proof photos are stored on the package itself rather than as ordinary
-- package_media rows. This keeps them out of the public media carousel and out
-- of the privacy-safe publicTracking projection. GraphQL also masks
-- proofPhotoUrl to package participants and staff only.
--
-- delivered_at is an immutable business timestamp. Unlike updated_at it does
-- not move when a proof photo or rating is attached, so the 5-minute proof
-- window and 30-day rating window remain stable.

ALTER TABLE packages
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS proof_photo_media_id UUID NULL,
    ADD COLUMN IF NOT EXISTS proof_photo_storage_path TEXT NULL,
    ADD COLUMN IF NOT EXISTS proof_photo_bucket VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS proof_photo_storage_mode VARCHAR(24) NULL;

-- Existing delivered packages predate delivered_at; recover it from the
-- DELIVERED lifecycle event.
UPDATE packages p
SET delivered_at = (
    SELECT e.created_at
    FROM package_events e
    WHERE e.package_id = p.id
      AND e.event_type = 'DELIVERED'
    ORDER BY e.created_at DESC
    LIMIT 1
)
WHERE p.delivered_at IS NULL
  AND p.status IN ('DELIVERED', 'COMPLETED');

CREATE INDEX IF NOT EXISTS idx_packages_delivered_at ON packages(delivered_at);

CREATE TABLE IF NOT EXISTS package_driver_ratings (
    id          UUID PRIMARY KEY,
    package_id  UUID NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
    driver_id   BIGINT NOT NULL REFERENCES users(id),
    client_id   BIGINT NOT NULL REFERENCES users(id),
    score       INTEGER NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment     TEXT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_package_driver_rating_client UNIQUE (package_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_package_driver_ratings_driver
    ON package_driver_ratings(driver_id, score);
