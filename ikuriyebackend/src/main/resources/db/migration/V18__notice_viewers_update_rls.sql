-- ── Row-Level Security: allow users to mark their own notices as read ─────
-- V12 enabled RLS on notice_viewers but only created a SELECT policy, so
-- clients writing read_at via Postgrest (Android's markRead) were silently
-- denied by RLS. Add the missing UPDATE policy so a user can mark their own
-- viewer rows as read (and only their own).
DO $$
BEGIN
  -- Only create the policy while user_id is still UUID (matching auth.uid()).
  -- V19 drops these policies and converts user_id to BIGINT; on a re-run
  -- against an already-migrated schema the comparison bigint = uuid would
  -- fail with "operator does not exist".
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'notice_viewers' AND policyname = 'notice_viewers_update_own'
  ) AND EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'notice_viewers' AND column_name = 'user_id' AND data_type = 'uuid'
  ) THEN
    CREATE POLICY notice_viewers_update_own ON notice_viewers
      FOR UPDATE
      USING (user_id = auth.uid())
      WITH CHECK (user_id = auth.uid());
  END IF;
END $$;
