-- V23: Make package_media.package_id nullable (media created before package linking).

ALTER TABLE package_media ALTER COLUMN package_id DROP NOT NULL;
