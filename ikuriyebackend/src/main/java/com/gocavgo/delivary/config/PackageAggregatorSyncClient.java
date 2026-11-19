package com.gocavgo.delivary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a minimal package projection to adminaggregate's key-guarded push
 * endpoint whenever a package lifecycle change is committed.
 *
 * <p>This mirrors the existing service-to-service push pattern used by
 * cavgomain ({@code AGGREGATOR_BASE_URL} + {@code AGGREGATOR_INTERNAL_API_KEY}).
 * No customer names, phones or addresses leave this service — only the IDs and
 * statuses needed for company-scoped "sent today by car/trip/driver" reporting.
 *
 * <p>If either setting is missing the client is disabled and every call is a
 * no-op, so local/test environments never attempt network I/O. Delivery is
 * fire-and-forget: a failed push is logged, never propagated to the package
 * mutation that triggered it.
 */
@Component
public class PackageAggregatorSyncClient {

    private static final Logger log = LoggerFactory.getLogger(PackageAggregatorSyncClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public PackageAggregatorSyncClient(
            @Value("${aggregator.base-url:}") String baseUrl,
            @Value("${aggregator.internal-api-key:}") String apiKey
    ) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? null : baseUrl.replaceAll("/+$", "");
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        if (isEnabled()) {
            log.info("PackageAggregatorSyncClient initialised — endpoint: {}/internal/packages", this.baseUrl);
        } else {
            log.info("PackageAggregatorSyncClient disabled — AGGREGATOR_BASE_URL / AGGREGATOR_INTERNAL_API_KEY not set");
        }
    }

    public boolean isEnabled() {
        return baseUrl != null && apiKey != null;
    }

    /** Minimal package fields needed by the aggregator for company reporting. */
    public record PackageSnapshot(
            UUID packageId,
            String trackingCode,
            String deliveryType,
            String packageOrigin,
            String status,
            UUID companyId,
            Long companyRefId,
            Long tripId,
            Instant createdAt,
            Instant updatedAt,
            List<CarrierLeg> carrierLegs
    ) {
    }

    /** Immutable driver/trip/vehicle snapshot for one custody handover. */
    public record CarrierLeg(
            UUID id,
            Long driverId,
            Long tripId,
            UUID companyId,
            Long companyRefId,
            Long vehicleId,
            Instant linkedAt,
            String fromStatus,
            String toStatus
    ) {
    }

    /** Fire-and-forget push; errors are logged and swallowed. */
    public void publishPackage(PackageSnapshot snapshot) {
        if (!isEnabled() || snapshot == null) {
            return;
        }
        try {
            var payload = toPayload(snapshot);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/packages"))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("x-internal-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            log.debug("Package sync OK for package={}, status={}", snapshot.packageId(), response.statusCode());
                        } else {
                            log.warn("Package sync returned status={} for package={} (body={})",
                                    response.statusCode(), snapshot.packageId(), response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("Package sync failed for package={}: {}", snapshot.packageId(), ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Package sync error for package={}: {}", snapshot.packageId(), e.getMessage());
        }
    }

    private Map<String, Object> toPayload(PackageSnapshot s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packageId", text(s.packageId()));
        payload.put("trackingCode", s.trackingCode());
        payload.put("deliveryType", s.deliveryType());
        payload.put("packageOrigin", s.packageOrigin());
        payload.put("status", s.status());
        payload.put("companyId", text(s.companyId()));
        payload.put("companyRefId", s.companyRefId() == null ? null : String.valueOf(s.companyRefId()));
        payload.put("tripId", s.tripId() == null ? null : String.valueOf(s.tripId()));
        payload.put("createdAt", s.createdAt() == null ? null : s.createdAt().toString());
        payload.put("updatedAt", s.updatedAt() == null ? null : s.updatedAt().toString());

        List<Map<String, Object>> legs = new ArrayList<>();
        for (CarrierLeg leg : s.carrierLegs() == null ? List.<CarrierLeg>of() : s.carrierLegs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", text(leg.id()));
            item.put("driverId", leg.driverId() == null ? null : String.valueOf(leg.driverId()));
            item.put("tripId", leg.tripId() == null ? null : String.valueOf(leg.tripId()));
            item.put("companyId", text(leg.companyId()));
            item.put("companyRefId", leg.companyRefId() == null ? null : String.valueOf(leg.companyRefId()));
            item.put("vehicleId", leg.vehicleId() == null ? null : String.valueOf(leg.vehicleId()));
            item.put("linkedAt", leg.linkedAt() == null ? null : leg.linkedAt().toString());
            item.put("fromStatus", leg.fromStatus());
            item.put("toStatus", leg.toStatus());
            legs.add(item);
        }
        payload.put("carrierLegs", legs);
        return payload;
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }
}
