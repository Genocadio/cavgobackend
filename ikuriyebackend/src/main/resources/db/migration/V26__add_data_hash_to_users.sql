-- Add dataHash column to users for Nexxauth dataHash-based conditional sync.
-- The Nexxauth org-access JWT includes a `dataHash` claim (UUID) that changes
-- on every non-password user mutation. The backend compares the token's hash
-- with the stored value to decide whether to re-fetch the user profile from
-- Nexxauth — avoiding a Nexxauth API call on every authenticated request.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS data_hash VARCHAR(64);
