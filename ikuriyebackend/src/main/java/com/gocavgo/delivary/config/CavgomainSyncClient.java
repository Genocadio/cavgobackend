package com.gocavgo.delivary.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client that syncs users with cavgomain.
 *
 * <p>For WORKER tokens the sync is <b>evaluative</b>: ikuriyebackend awaits the
 * user as cavgomain serves it (with office data) so it can extract the office
 * location id. The call must stay fast and stable:
 * <ul>
 *   <li>User unknown to cavgomain ({@code 404}) → returns {@code null}, caller
 *       falls back to the plain Nexxauth provisioning instead of waiting.</li>
 *   <li>Error / timeout → logs and returns {@code null} (never thrown up), so a
 *       slow cavgomain can't fail an ikuriye auth request.</li>
 * </ul>
 *
 * <p>The legacy {@link #syncUser(Long)} fire-and-forget mirror is kept for
 * DRIVER tokens so cavgomain stays aware of them.
 *
 * <p>If {@code cavgomain.base-url} is not configured, all sync is silently
 * skipped.
 */
@Component
public class CavgomainSyncClient {

    private static final Logger log = LoggerFactory.getLogger(CavgomainSyncClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
     * A user as served back by cavgomain's internal sync endpoint. Only the
     * fields needed to extract the office location id are mapped.
     */
    public record CavgomainUser(Long id, CavgomainOffice office) {
        public record CavgomainOffice(
                String officeLocationId,
                String companyCode,
                String name
        ) {
        }
    }

    /**
     * Evaluative sync: calls cavgomain's internal sync endpoint synchronously
     * and returns the user with office data, or {@code null} when the user is
     * unknown to cavgomain (404) or the call fails for any reason.
     *
     * @param userId the Nexxauth org-user id to sync
     */
    public CavgomainUser syncUserAndGetResult(Long userId) {
        if (baseUrl == null || userId == null) {
            return null;
        }
        try {
            var body = "{\"userId\":" + userId + "}";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/api/users/sync"))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                log.info("Cavgomain sync: user not found (404), userId={}", userId);
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Cavgomain sync returned status={} for userId={}", response.statusCode(), userId);
                return null;
            }
            return MAPPER.readValue(response.body(), CavgomainUser.class);
        } catch (Exception e) {
            log.warn("Cavgomain sync failed for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Legacy fire-and-forget sync — sends the user to cavgomain so it mirrors
     * the same user. Non-blocking; errors are logged and swallowed.
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
