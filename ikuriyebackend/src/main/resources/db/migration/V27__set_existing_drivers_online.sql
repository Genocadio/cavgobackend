-- Set all existing OFFLINE driver profiles to ONLINE.
-- The default was changed from OFFLINE to ONLINE in UserService.upsertDriverProfile(),
-- but existing rows in the DB still have the old OFFLINE default.
UPDATE driver_profiles SET status = 'ONLINE' WHERE status = 'OFFLINE';
