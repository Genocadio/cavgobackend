CREATE TABLE IF NOT EXISTS packages (
    id            UUID PRIMARY KEY,
    tracking_code VARCHAR(20) NOT NULL UNIQUE,
    delivery_type VARCHAR(20) NOT NULL,
    status        VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    creator_id    UUID NOT NULL REFERENCES users(id),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_packages_tracking_code ON packages(tracking_code);
CREATE INDEX IF NOT EXISTS idx_packages_creator_id ON packages(creator_id);
CREATE INDEX IF NOT EXISTS idx_packages_status ON packages(status);

CREATE TABLE IF NOT EXISTS package_custodians (
    id               UUID PRIMARY KEY,
    package_id       UUID NOT NULL REFERENCES packages(id),
    user_id          UUID NOT NULL REFERENCES users(id),
    role             VARCHAR(20) NOT NULL,
    assigned_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    handover_token_hash VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_custodians_package ON package_custodians(package_id);
CREATE INDEX IF NOT EXISTS idx_custodians_user ON package_custodians(user_id);
CREATE INDEX IF NOT EXISTS idx_custodians_role ON package_custodians(package_id, role);

CREATE TABLE IF NOT EXISTS package_people (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES packages(id),
    role       VARCHAR(20) NOT NULL,
    user_id    UUID REFERENCES users(id),
    name       VARCHAR(255),
    phone      VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_package_people_package ON package_people(package_id);
CREATE INDEX IF NOT EXISTS idx_package_people_user ON package_people(user_id);

CREATE TABLE IF NOT EXISTS package_locations (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES packages(id),
    type       VARCHAR(20) NOT NULL,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    place_name VARCHAR(255),
    place_id   VARCHAR(255),
    office_id  UUID
);

CREATE INDEX IF NOT EXISTS idx_package_locations_package ON package_locations(package_id);

CREATE TABLE IF NOT EXISTS package_details (
    id             UUID PRIMARY KEY,
    package_id     UUID NOT NULL UNIQUE REFERENCES packages(id),
    category       VARCHAR(100),
    description    TEXT,
    fragile        BOOLEAN NOT NULL DEFAULT FALSE,
    weight         DOUBLE PRECISION,
    length         DOUBLE PRECISION,
    width          DOUBLE PRECISION,
    height         DOUBLE PRECISION,
    declared_value DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS package_events (
    id          UUID PRIMARY KEY,
    package_id  UUID NOT NULL REFERENCES packages(id),
    event_type  VARCHAR(30) NOT NULL,
    actor_id    UUID NOT NULL REFERENCES users(id),
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_package_events_package ON package_events(package_id);

CREATE TABLE IF NOT EXISTS package_custody (
    id           UUID PRIMARY KEY,
    package_id   UUID NOT NULL REFERENCES packages(id),
    from_entity  VARCHAR(100) NOT NULL,
    to_entity    VARCHAR(100) NOT NULL,
    from_role    VARCHAR(20),
    to_role      VARCHAR(20),
    timestamp    TIMESTAMP NOT NULL DEFAULT NOW(),
    notes        TEXT
);

CREATE INDEX IF NOT EXISTS idx_package_custody_package ON package_custody(package_id);

CREATE TABLE IF NOT EXISTS pickup_codes (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL UNIQUE REFERENCES packages(id),
    code_hash  VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP,
    used_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS handover_tokens (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL UNIQUE REFERENCES packages(id),
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    used_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_handover_tokens_package ON handover_tokens(package_id);
