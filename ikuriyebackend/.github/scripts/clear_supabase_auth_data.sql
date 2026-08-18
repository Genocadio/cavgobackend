-- ── Pre-migration cleanup for V19 (Supabase → Nexxauth) ────────────────────
-- V19 changes users.id (and every user-referencing FK column) from UUID to
-- BIGINT. Existing Supabase-era users cannot be mapped to Nexxauth ids, so the
-- migration refuses to run while the users table is non-empty.
--
-- This script deletes all user-linked business data in FK-safe order:
--   psql $DATABASE_URL -f .github/scripts/clear_supabase_auth_data.sql
--
-- What survives: offices and standalone package_locations (not tied to a user).
-- What is deleted: users, worker/driver profiles, packages + all their
-- children, transfers, and notices.
--
-- ⚠️ Destructive — run only if you accept losing the user-linked history.

BEGIN;

DELETE FROM notice_viewers;
DELETE FROM notices;
DELETE FROM transfer_packages;
DELETE FROM transfers;
DELETE FROM delivery_codes;
DELETE FROM package_custodians;
DELETE FROM package_people;
DELETE FROM package_events;
DELETE FROM package_custody;
DELETE FROM package_details;
DELETE FROM packages;
DELETE FROM driver_profiles;
DELETE FROM worker_profiles;
DELETE FROM users;

COMMIT;
