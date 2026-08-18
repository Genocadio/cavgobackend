-- CREATOR was removed from CustodianRole.
-- Rows written before this change have role = 'CREATOR', which Hibernate can no longer deserialize.
-- These rows were written only for WORKER and DRIVER creators; CUSTOMER creators were never
-- custodians in the intended model. Since the creator_id is already stored on the packages table,
-- we can derive the correct role by joining to the users table.
--
-- Rule:
--   custodian.role = 'CREATOR' AND users.role = 'DRIVER'  -> 'DRIVER'
--   custodian.role = 'CREATOR' AND users.role = 'WORKER'  -> 'WORKER'
--   custodian.role = 'CREATOR' AND users.role = anything else -> delete the row
--     (CUSTOMER creators are not custodians; these rows should not exist but are cleaned up safely)
--
-- On a re-run against an already-migrated schema (e.g. after flyway_schema_history
-- was reset) the users.role column may no longer exist (V20). Guard accordingly.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'role'
  ) THEN
    UPDATE package_custodians pc
    SET role = u.role
    FROM users u
    WHERE pc.role = 'CREATOR'
      AND pc.user_id = u.id
      AND u.role IN ('WORKER', 'DRIVER');
  END IF;
END $$;

DELETE FROM package_custodians
WHERE role = 'CREATOR';
