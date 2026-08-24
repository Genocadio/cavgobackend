-- V22: Add storage_mode to track whether media is stored locally or in Supabase.
-- 'supabase' = file is in Supabase Storage (signed URL needed)
-- 'local'    = file is on the backend's local disk (served directly)
-- NULL       = legacy row, assume 'supabase' for backward compat

ALTER TABLE package_media ADD COLUMN storage_mode VARCHAR(16) DEFAULT 'supabase';
ALTER TABLE users ADD COLUMN avatar_storage_mode VARCHAR(16) DEFAULT 'supabase';
