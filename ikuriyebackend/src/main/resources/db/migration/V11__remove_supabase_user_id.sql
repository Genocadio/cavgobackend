-- User ID is now the Supabase user ID directly (no more auto-generated UUID).
-- The supabase_user_id column is redundant — user.id serves as both the PK and the Supabase auth ID.
--
-- 🛑 GUARD: Fail loudly if any existing user row has id != supabase_user_id.
-- Without this guard, dropping the column would leave users with mismatched IDs,
-- and syncUser would silently create duplicate records (findById with the Supabase
-- UUID would return empty, triggering a new user creation).
--
-- If this migration fails, you must run a data migration first that copies
-- supabase_user_id → id and cascades the FK references, then retry this migration.
-- The column is only present the first time this migration runs. On a re-run
-- against an already-migrated schema (e.g. after flyway_schema_history was
-- reset) it no longer exists, so skip the whole block unless it is present.
DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'supabase_user_id'
  ) THEN
    -- 🛑 GUARD: Fail loudly if any existing user row has id != supabase_user_id.
    -- Without this guard, dropping the column would leave users with mismatched IDs,
    -- and syncUser would silently create duplicate records (findById with the Supabase
    -- UUID would return empty, triggering a new user creation).
    --
    -- If this migration fails, you must run a data migration first that copies
    -- supabase_user_id → id and cascades the FK references, then retry this migration.
    IF EXISTS (SELECT 1 FROM users WHERE id IS DISTINCT FROM supabase_user_id) THEN
      RAISE EXCEPTION 'Existing user rows have mismatched id/supabase_user_id. Run ''.github/scripts/migrate_user_ids.sql'' (psql -d $DATABASE_URL -f .github/scripts/migrate_user_ids.sql) first, then retry this migration.';
    END IF;

    ALTER TABLE users DROP COLUMN supabase_user_id;
  END IF;
END $$;

DROP INDEX IF EXISTS idx_users_supabase_user_id;
