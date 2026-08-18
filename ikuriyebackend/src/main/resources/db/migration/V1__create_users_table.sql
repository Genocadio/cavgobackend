CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY,
    supabase_user_id UUID NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    phone           VARCHAR(50),
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    role            VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- supabase_user_id is dropped by V11. If this migration is re-run against an
-- already-migrated schema (e.g. after flyway_schema_history was reset) the
-- column no longer exists, so only create the index while the column does.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'supabase_user_id'
  ) THEN
    CREATE INDEX IF NOT EXISTS idx_users_supabase_user_id ON users(supabase_user_id);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- role column is dropped by V20. Guard the index creation for re-runs.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'role'
  ) THEN
    CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
