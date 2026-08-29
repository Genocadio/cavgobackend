-- V25: Enforce that a package can only be in ONE open transfer at a time.
--
-- Rationale: The application code in TransferService.assertPackageNotInOpenTransfer
-- already handles the same-owner case (releases the package from the old transfer),
-- but a database-level trigger prevents race conditions and ensures data integrity
-- even if multiple transactions run concurrently.

-- ── Step 1: Clean up existing duplicates ──────────────────────────────────────
-- Find packages that appear in more than one open transfer and remove the older
-- link (keep the most recent one).

WITH open_links AS (
    SELECT tp.id AS link_id,
           tp.package_id,
           tp.transfer_id,
           tp.added_at,
           ROW_NUMBER() OVER (
               PARTITION BY tp.package_id
               ORDER BY tp.added_at DESC
           ) AS rn
    FROM transfer_packages tp
    JOIN transfers t ON t.id = tp.transfer_id
    WHERE t.status IN ('PENDING', 'REQUESTED')
)
DELETE FROM transfer_packages
WHERE id IN (
    SELECT link_id FROM open_links WHERE rn > 1
);

-- Cancel any transfers that are now empty after cleanup
UPDATE transfers t
SET status = 'CANCELED',
    updated_at = NOW()
WHERE t.status IN ('PENDING', 'REQUESTED')
  AND NOT EXISTS (
      SELECT 1 FROM transfer_packages tp WHERE tp.transfer_id = t.id
  );

-- ── Step 2: Create the trigger function ───────────────────────────────────────

CREATE OR REPLACE FUNCTION fn_enforce_single_open_transfer()
RETURNS TRIGGER AS $$
DECLARE
    existing_transfer_id UUID;
    existing_creator_id  BIGINT;
BEGIN
    -- Check if this package already exists in an open transfer
    SELECT t.id, t.creator_id
    INTO existing_transfer_id, existing_creator_id
    FROM transfer_packages tp
    JOIN transfers t ON t.id = tp.transfer_id
    WHERE tp.package_id = NEW.package_id
      AND t.status IN ('PENDING', 'REQUESTED')
    LIMIT 1;

    IF existing_transfer_id IS NOT NULL THEN
        RAISE EXCEPTION
            'Package % is already in open transfer % (status=PENDING/REQUESTED). '
            'Only the transfer creator can move it.',
            NEW.package_id, existing_transfer_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── Step 3: Attach the trigger ────────────────────────────────────────────────

DROP TRIGGER IF EXISTS trg_enforce_single_open_transfer ON transfer_packages;

CREATE TRIGGER trg_enforce_single_open_transfer
    BEFORE INSERT ON transfer_packages
    FOR EACH ROW
    EXECUTE FUNCTION fn_enforce_single_open_transfer();
