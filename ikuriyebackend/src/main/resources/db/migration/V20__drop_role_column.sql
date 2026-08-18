-- Roles are no longer stored locally. They are sourced exclusively from the JWT
-- token's roles claim and verified by NexxauthJwtAuthenticationFilter. This
-- removes the redundant role column; RBAC continues to work via @PreAuthorize
-- checks against the token-derived Spring authorities.
ALTER TABLE users DROP COLUMN IF EXISTS role;
