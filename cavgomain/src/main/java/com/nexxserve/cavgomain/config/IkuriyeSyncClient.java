package com.nexxserve.cavgomain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Fire-and-forget client that pushes a WORKER's office location id to
 * ikuriyebackend whenever its office assignment is created or changed here in
 * cavgomain.
 *
 * <p>Ikuriye stores the id as the worker's FK-free {@code company_id} (the
 * office location reference). The push is non-blocking and error-guarded — it
 * never fails the office-assignment flow in cavgomain.
 *
 * <p>If {@code ikuriye.base-url} is not configured, the push is silently
 * skipped.
 */
@Component
public class IkuriyeSyncClient {

    private static final Logger log = LoggerFactory.getLogger(IkuriyeSyncClient.class);

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    public IkuriyeSyncClient(
            @Value("${ikuriye.base-url:}") String baseUrl
    ) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? null
                : baseUrl.replaceAll("/+$", "");
        if (this.baseUrl != null) {
            log.info("IkuriyeSyncClient initialised — push endpoint: {}/internal/api/users/office-sync", this.baseUrl);
        } else {
            log.info("IkuriyeSyncClient disabled — IKURIYE_BASE_URL not set");
        }
    }

    /**
     * Pushes a worker's office location id to ikuriye. When {@code officeLocationId}
     * is {@code null} the worker's office is being cleared, so ikuriye clears the
     * stored location id.
     */
    public void pushWorkerOffice(Long userId, UUID officeLocationId) {
        if (baseUrl == null || userId == null) {
            return;
        }
        try {
            String body = "{\"userId\":" + userId
                    + ",\"officeLocationId\":" + (officeLocationId == null ? "null" : "\"" + officeLocationId + "\"")
                    + "}";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/api/users/office-sync"))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            log.debug("Ikuriye office push OK for userId={}, officeLocationId={}, status={}",
                                    userId, officeLocationId, response.statusCode());
                        } else {
                            log.warn("Ikuriye office push returned status={} for userId={}, officeLocationId={}",
                                    response.statusCode(), userId, officeLocationId);
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("Ikuriye office push failed for userId={}, officeLocationId={}: {}",
                                userId, officeLocationId, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Ikuriye office push error for userId={}: {}", userId, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return baseUrl != null;
    }
}