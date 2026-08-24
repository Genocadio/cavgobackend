-- Add storage_path and bucket columns to package_media for backend-proxied uploads.
-- The existing 'url' column is kept for backward compatibility but will be
-- deprecated. New uploads use storage_path + bucket; the backend generates
-- signed URLs on-the-fly so clients never see Supabase URLs.
ALTER TABLE package_media
    ADD COLUMN IF NOT EXISTS storage_path TEXT,
    ADD COLUMN IF NOT EXISTS bucket VARCHAR(64);

-- Add avatar storage columns to users. The backend stores the storage path
-- and generates signed URLs when clients request the user profile.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS avatar_storage_path TEXT,
    ADD COLUMN IF NOT EXISTS avatar_bucket VARCHAR(64);
