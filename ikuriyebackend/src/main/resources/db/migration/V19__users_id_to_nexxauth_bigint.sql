-- ── User IDs are now Nexxauth org-user ids (BIGINT) ────────────────────────
-- Auth moved from Supabase GoTrue to Nexxauth. The org-access JWT's `sub`
-- claim is the Nexxauth org-user id (a number), so users.id — and every column
-- referencing it — changes from UUID to BIGINT.
--
-- 🛑 GUARD: Existing Supabase-era users cannot be mapped to Nexxauth ids (they
-- don't exist in Nexxauth), so the migration FAILS if any user rows exist.
-- Run the companion cleanup script first (it deletes user-linked business
-- data — packages, transfers, notices — in FK-safe order):
--
--     psql $DATABASE_URL -f .github/scripts/clear_supabase_auth_data.sql
--
-- then retry this migration. Offices and package_locations rows (standalone
-- locations, not tied to a user) survive.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM users) THEN
    RAISE EXCEPTION 'users table is not empty. Existing Supabase users cannot be mapped to Nexxauth ids. Run .github/scripts/clear_supabase_auth_data.sql first, then retry this migration.';
  END IF;
END $$;

-- ── Drop FK constraints on user-id columns (required before ALTER TYPE) ────
ALTER TABLE worker_profiles    DROP CONSTRAINT IF EXISTS worker_profiles_user_id_fkey;
ALTER TABLE driver_profiles    DROP CONSTRAINT IF EXISTS driver_profiles_user_id_fkey;
ALTER TABLE packages           DROP CONSTRAINT IF EXISTS packages_creator_id_fkey;
ALTER TABLE package_custodians DROP CONSTRAINT IF EXISTS package_custodians_user_id_fkey;
ALTER TABLE package_people     DROP CONSTRAINT IF EXISTS package_people_user_id_fkey;
ALTER TABLE package_events     DROP CONSTRAINT IF EXISTS package_events_actor_id_fkey;
ALTER TABLE transfers          DROP CONSTRAINT IF EXISTS transfers_creator_id_fkey;
ALTER TABLE transfer_packages  DROP CONSTRAINT IF EXISTS transfer_packages_added_by_fkey;

-- ── Drop RLS policies BEFORE altering the column types ─────────────────────
-- The V12/V18 policies reference notice_viewers.user_id, so ALTER TYPE on that
-- column fails while they exist ("cannot alter type of a column used in a
-- policy definition"). Supabase Realtime is gone anyway — notices reach clients
-- via GraphQL polling and the backend owns the table.
-- Drop ALL RLS policies on notice_viewers, not just specific names — the real
-- DB may have different policy names than the current repo (e.g. older V18).
ALTER TABLE notice_viewers DISABLE ROW LEVEL SECURITY;
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN (SELECT policyname FROM pg_policies WHERE tablename = 'notice_viewers') LOOP
    EXECUTE format('DROP POLICY IF EXISTS %I ON notice_viewers', r.policyname);
  END LOOP;
END $$;

-- ── users.id: UUID → BIGINT ────────────────────────────────────────────────
-- USING is required: there is no UUID→bigint cast (not even an explicit one),
-- so without USING the ALTER fails even on an empty table. uuid::text::bigint
-- is safe here because the guard above guarantees the table is empty.
ALTER TABLE users ALTER COLUMN id TYPE BIGINT USING id::text::bigint;

-- ── user-referencing columns: UUID → BIGINT ────────────────────────────────
ALTER TABLE worker_profiles    ALTER COLUMN user_id TYPE BIGINT USING user_id::text::bigint;
ALTER TABLE driver_profiles    ALTER COLUMN user_id TYPE BIGINT USING user_id::text::bigint;
ALTER TABLE packages           ALTER COLUMN creator_id TYPE BIGINT USING creator_id::text::bigint;
ALTER TABLE package_custodians ALTER COLUMN user_id TYPE BIGINT USING user_id::text::bigint;
ALTER TABLE package_people     ALTER COLUMN user_id TYPE BIGINT USING user_id::text::bigint;
ALTER TABLE package_events     ALTER COLUMN actor_id TYPE BIGINT USING actor_id::text::bigint;
ALTER TABLE transfers          ALTER COLUMN creator_id TYPE BIGINT USING creator_id::text::bigint;
ALTER TABLE transfers          ALTER COLUMN match_user_id TYPE BIGINT USING match_user_id::text::bigint;
ALTER TABLE transfers          ALTER COLUMN requestor_id TYPE BIGINT USING requestor_id::text::bigint;
ALTER TABLE transfer_packages  ALTER COLUMN added_by TYPE BIGINT USING added_by::text::bigint;
ALTER TABLE notices            ALTER COLUMN actor_id TYPE BIGINT USING actor_id::text::bigint;
ALTER TABLE notice_viewers     ALTER COLUMN user_id TYPE BIGINT USING user_id::text::bigint;

-- ── Re-add the FK constraints ───────────────────────────────────────────────
ALTER TABLE worker_profiles
    ADD CONSTRAINT worker_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE driver_profiles
    ADD CONSTRAINT driver_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE packages
    ADD CONSTRAINT packages_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES users(id);
ALTER TABLE package_custodians
    ADD CONSTRAINT package_custodians_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE package_people
    ADD CONSTRAINT package_people_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE package_events
    ADD CONSTRAINT package_events_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);
ALTER TABLE transfers
    ADD CONSTRAINT transfers_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES users(id);
ALTER TABLE transfer_packages
    ADD CONSTRAINT transfer_packages_added_by_fkey FOREIGN KEY (added_by) REFERENCES users(id);

