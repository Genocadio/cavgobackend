-- Custody is now proven by current-custodian identity checks instead of
-- one-time handover tokens (see PackageService.validateStatusActor /
-- validateAssignerHoldsCustody). Remove the token storage.

DROP TABLE IF EXISTS handover_tokens;

ALTER TABLE package_custodians DROP COLUMN IF EXISTS handover_token_hash;
