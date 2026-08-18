ALTER TABLE users ADD COLUMN IF NOT EXISTS username VARCHAR(100) UNIQUE;

CREATE TABLE IF NOT EXISTS package_media (
    id         UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES packages(id),
    url        TEXT NOT NULL,
    media_type VARCHAR(20) NOT NULL, -- 'VIDEO' or 'PICTURE'
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_package_media_package ON package_media(package_id);
