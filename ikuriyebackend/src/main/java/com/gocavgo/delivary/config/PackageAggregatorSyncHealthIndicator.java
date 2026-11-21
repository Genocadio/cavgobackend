package com.gocavgo.delivary.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Surfaces the package-snapshot push pipeline on {@code /actuator/health}.
 *
 * <p>The push path is deliberately fire-and-forget so a broken aggregator can
 * never fail a package mutation. That also made the original misconfiguration
 * (missing {@code AGGREGATOR_BASE_URL} / {@code AGGREGATOR_INTERNAL_API_KEY})
 * invisible until someone noticed company reports were empty. Reporting both
 * the configuration state and the consecutive-failure streak here turns those
 * silent failures into a health signal monitors can alert on.
 *
 * <p>Only non-secret details are exposed; the shared API key is never included.
 */
@Component("packageAggregatorSync")
public class PackageAggregatorSyncHealthIndicator implements HealthIndicator {

    private final PackageAggregatorSyncClient client;

    public PackageAggregatorSyncHealthIndicator(PackageAggregatorSyncClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        Health.Builder builder;
        if (!client.isEnabled()) {
            builder = Health.down()
                    .withDetail("enabled", false)
                    .withDetail("reason", "AGGREGATOR_BASE_URL / AGGREGATOR_INTERNAL_API_KEY not set");
        } else if (client.isDegraded()) {
            builder = Health.down()
                    .withDetail("enabled", true)
                    .withDetail("reason", "consecutive push failures reached the configured threshold")
                    .withDetail("consecutiveFailures", client.getConsecutiveFailures())
                    .withDetail("failureThreshold", client.getFailureThreshold());
            addInstant(builder, "lastFailureAt", client.getLastFailureAt());
            addString(builder, "lastFailure", client.getLastFailureDetail());
        } else {
            builder = Health.up()
                    .withDetail("enabled", true)
                    .withDetail("consecutiveFailures", client.getConsecutiveFailures())
                    .withDetail("failureThreshold", client.getFailureThreshold());
            addInstant(builder, "lastSuccessAt", client.getLastSuccessAt());
            addInstant(builder, "lastFailureAt", client.getLastFailureAt());
            addString(builder, "lastFailure", client.getLastFailureDetail());
        }
        addString(builder, "endpoint", client.getEndpoint());
        return builder.build();
    }

    private static void addInstant(Health.Builder builder, String key, Instant value) {
        if (value != null) {
            builder.withDetail(key, value.toString());
        }
    }

    private static void addString(Health.Builder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.withDetail(key, value);
        }
    }
}
