CREATE TABLE IF NOT EXISTS transfers (
    id                 UUID PRIMARY KEY,
    creator_id         UUID NOT NULL REFERENCES users(id),
    rule_type          VARCHAR(20) NOT NULL,          -- 'AUTO' / 'SECURE' / 'CONFIRM'
    match_company_id   UUID,                          -- nullable: filter by company
    match_user_id      UUID,                          -- nullable: filter by user
    requestor_id       UUID,                          -- nullable: user who requested (CONFIRM mode)
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / REQUESTED / DONE / CANCELED
    transfer_code_hash VARCHAR(64),                   -- SHA-256 hash for SECURE transfers
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transfers_creator ON transfers(creator_id);
CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status);
CREATE INDEX IF NOT EXISTS idx_transfers_rule_type ON transfers(rule_type);

CREATE TABLE IF NOT EXISTS transfer_packages (
    id          UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    package_id  UUID NOT NULL REFERENCES packages(id),
    added_by    UUID NOT NULL REFERENCES users(id),
    added_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_transfer_package UNIQUE (package_id)
);

CREATE INDEX IF NOT EXISTS idx_transfer_packages_transfer ON transfer_packages(transfer_id);
CREATE INDEX IF NOT EXISTS idx_transfer_packages_package ON transfer_packages(package_id);
