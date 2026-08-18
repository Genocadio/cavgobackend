package com.gocavgo.delivary.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs {@code flyway.repair()} before {@code flyway.migrate()} on every startup.
 *
 * <p>{@code repair()} updates the checksums stored in {@code flyway_schema_history}
 * so that they match the current migration files on disk.  This lets the app start
 * cleanly even when already-applied migrations (V1, V7, …) were edited to make them
 * idempotent — Flyway no longer refuses to start with a checksum-mismatch error.
 *
 * <p>Spring Boot's auto-configured {@code FlywayMigrationInitializer} detects this
 * bean via {@code ObjectProvider<FlywayMigrationStrategy>} and uses it instead of
 * the default {@code Flyway::migrate} strategy.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
