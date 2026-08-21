-- ── notices: one row per event ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notices (
    id            UUID PRIMARY KEY,
    resource_type VARCHAR(20)  NOT NULL,       -- 'PACKAGE' or 'TRANSFER'
    resource_id   UUID         NOT NULL,
    event_type    VARCHAR(40)  NOT NULL,
    actor_id      UUID,
    title         VARCHAR(255) NOT NULL,
    message       TEXT         NOT NULL,
    payload       JSONB,
    expires_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notices_resource ON notices(resource_type, resource_id);

-- ── notice_viewers: one row per recipient per notice ────────────────────
CREATE TABLE IF NOT EXISTS notice_viewers (
    id           UUID PRIMARY KEY,
    notice_id    UUID      NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    user_id      UUID      NOT NULL,
    delivered_at TIMESTAMP,
    read_at      TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notice_viewer ON notice_viewers(notice_id, user_id);
CREATE INDEX IF NOT EXISTS idx_notice_viewer_user ON notice_viewers(user_id, created_at DESC);

-- ── Row-Level Security for Supabase Realtime ────────────────────────────
-- The frontend subscribes to notice_viewers with filter: user_id=eq.<uid>
ALTER TABLE notice_viewers ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  -- Only create the policy while user_id is still UUID (matching auth.uid())
  -- AND the auth.uid() function exists (Supabase installs it; plain PG does not).
  -- V19 drops these policies and converts user_id to BIGINT; on a re-run
  -- against an already-migrated schema the comparison bigint = uuid would
  -- fail with "operator does not exist".
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'notice_viewers' AND policyname = 'notice_viewers_select_own'
  ) AND EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'notice_viewers' AND column_name = 'user_id' AND data_type = 'uuid'
  ) AND EXISTS (
    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid
    WHERE n.nspname = 'auth' AND p.proname = 'uid'
  ) THEN
    CREATE POLICY notice_viewers_select_own ON notice_viewers
      FOR SELECT
      USING (user_id = auth.uid());
  END IF;
END $$;
