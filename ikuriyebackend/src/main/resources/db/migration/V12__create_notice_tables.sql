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
