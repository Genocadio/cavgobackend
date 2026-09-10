-- Relax the legacy NOT NULL constraints on users.first_name / users.last_name.
--
-- Why: the original User entity declared the name columns nullable=false, so
-- Hibernate's ddl-auto created them NOT NULL. The entity no longer carries
-- those flags, but ddl-auto: update never REMOVES existing constraints, so the
-- production table kept NOT NULL and Nexxauth profiles without a last name
-- (single-name accounts) fail the inline-sync INSERT with SQLState 23502 —
-- blocking the user's entire session (see NexxauthJwtAuthenticationFilter).
--
-- cavgomain has no Flyway; this script runs at every startup via
-- spring.sql.init AFTER Hibernate's schema update
-- (spring.jpa.defer-datasource-initialization: true), so the users table
-- always exists by this point. `IF EXISTS` keeps the script harmless in
-- contexts where it runs before Hibernate's DDL (e.g. @DataJpaTest slices),
-- and DROP NOT NULL on an already-nullable column is a no-op, so it is safe
-- to re-run on every boot.
ALTER TABLE IF EXISTS users ALTER COLUMN first_name DROP NOT NULL;
ALTER TABLE IF EXISTS users ALTER COLUMN last_name DROP NOT NULL;
