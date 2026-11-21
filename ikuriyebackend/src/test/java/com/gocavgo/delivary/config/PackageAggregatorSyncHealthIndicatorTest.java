package com.gocavgo.delivary.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageAggregatorSyncHealthIndicatorTest {

    @Test
    void missingConfigurationReportsDown() {
        var client = new PackageAggregatorSyncClient(null, null, 3);
        var indicator = new PackageAggregatorSyncHealthIndicator(client);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("enabled"));
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("AGGREGATOR_BASE_URL"));
        assertFalse(health.getDetails().containsKey("endpoint"));
    }

    @Test
    void configuredClientWithNoFailuresReportsUp() {
        var client = new PackageAggregatorSyncClient("http://adminaggregate:4000/", "key", 3);
        var indicator = new PackageAggregatorSyncHealthIndicator(client);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("enabled"));
        assertEquals(0, health.getDetails().get("consecutiveFailures"));
        assertEquals("http://adminaggregate:4000/internal/packages", health.getDetails().get("endpoint"));
        assertFalse(health.getDetails().toString().contains("key"));
    }

    @Test
    void failuresBelowThresholdStayUp() {
        var client = new PackageAggregatorSyncClient("http://adminaggregate:4000", "key", 3);
        var indicator = new PackageAggregatorSyncHealthIndicator(client);
        client.recordFailure("HTTP 500");
        client.recordFailure("HTTP 503");

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(2, health.getDetails().get("consecutiveFailures"));
        assertEquals("HTTP 503", health.getDetails().get("lastFailure"));
    }

    @Test
    void failuresAtThresholdReportDown() {
        var client = new PackageAggregatorSyncClient("http://adminaggregate:4000", "key", 3);
        var indicator = new PackageAggregatorSyncHealthIndicator(client);
        client.recordFailure("HTTP 500");
        client.recordFailure("HTTP 500");
        client.recordFailure("connect timeout");

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(3, health.getDetails().get("consecutiveFailures"));
        assertEquals(3, health.getDetails().get("failureThreshold"));
        assertEquals("connect timeout", health.getDetails().get("lastFailure"));
        assertTrue(health.getDetails().containsKey("lastFailureAt"));
    }

    @Test
    void successResetsFailureStreak() {
        var client = new PackageAggregatorSyncClient("http://adminaggregate:4000", "key", 2);
        var indicator = new PackageAggregatorSyncHealthIndicator(client);
        client.recordFailure("HTTP 500");
        client.recordSuccess();

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, health.getDetails().get("consecutiveFailures"));
        assertFalse(health.getDetails().containsKey("lastFailure"));
        assertTrue(health.getDetails().containsKey("lastSuccessAt"));
    }
}
