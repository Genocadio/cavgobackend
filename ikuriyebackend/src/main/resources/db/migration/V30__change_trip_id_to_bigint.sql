ALTER TABLE packages ALTER COLUMN trip_id TYPE BIGINT USING (
    CASE
        WHEN trip_id IS NULL THEN NULL
        WHEN trip_id::text ~ '^[0-9]+$' THEN trip_id::text::bigint
        ELSE NULL
    END
);

ALTER TABLE transfers ALTER COLUMN trip_id TYPE BIGINT USING (
    CASE
        WHEN trip_id IS NULL THEN NULL
        WHEN trip_id::text ~ '^[0-9]+$' THEN trip_id::text::bigint
        ELSE NULL
    END
);
