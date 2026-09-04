package com.gocavgo.delivary.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Fire-and-forget client that syncs users to cavgomain when a WORKER or DRIVER
 * authenticates through ikuriyebackend. Ensures cavgomain always mirrors the
 * same workers/drivers that ikuriye serves.
 *
 * <p>If {@code cavgomain.base-url} is not configured, sync is silently skipped.
 * The sync is non-blocking — the caller never waits for the response.
 */
@Component
public class CavgomainSyncClient {

    private static final Logger log = LoggerFactory.getLogger(CavgomainSyncClient.class);

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    public CavgomainSyncClient(
            @Value("${cavgomain.base-url:}") String baseUrl
    ) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? null
                : baseUrl.replaceAll("/+$", "");
        if (this.baseUrl != null) {
            log.info("CavgomainSyncClient initialised — endpoint: {}/internal/api/users/sync", this.baseUrl);
        } else {
            log.info("CavgomainSyncClient disabled — CAVGOMAIN_BASE_URL not set");
        }
    }

    /**
     * Sends a non-blocking sync request to cavgomain for the given user.
     * If the base URL is not configured, this is a no-op.
     *
     * @param userId the Nexxauth org-user id to sync
     */
    public void syncUser(Long userId) {
        if (baseUrl == null) {
            // cavgomain is not configured — skip silently
            return;
        }

        try {
            var body = "{\"userId\":" + userId + "}";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/api/users/sync"))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

            // Fire-and-forget: send asynchronously, don't block the caller
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            log.debug("Cavgomain sync OK for userId={}, status={}", userId, response.statusCode());
                        } else {
                            log.warn("Cavgomain sync returned status={} for userId={}", response.statusCode(), userId);
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("Cavgomain sync failed for userId={}: {}", userId, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            // Should never happen with sendAsync, but log just in case
            log.warn("Cavgomain sync error for userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Returns true if cavgomain sync is configured and active.
     */
    public boolean isEnabled() {
        return baseUrl != null;
    }
}
