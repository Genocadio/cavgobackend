-- V31: Create idempotency_records table to cache mutation responses
-- Prevents duplicate execution on network drops/retries for key mutations

CREATE TABLE IF NOT EXISTS idempotency_records (
    key VARCHAR(128) NOT NULL,
    actor_id BIGINT NOT NULL,
    mutation_name VARCHAR(64) NOT NULL,
    response_payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (key, actor_id, mutation_name)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_created_at ON idempotency_records(created_at);
