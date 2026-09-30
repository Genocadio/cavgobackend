package com.gocavgo.delivary.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates all required Nexxauth configuration at startup by:
 * <ol>
 *   <li>Checking that all required environment variables are set</li>
 *   <li>Making a live API call to {@code GET /organisations/roles}
 *       (SERVER client auth) — validates client-id, client-token, and that the
 *       client belongs to the configured organisation</li>
 * </ol>
 * If any check fails the application refuses to start.
 */
@Component
@org.springframework.context.annotation.Profile("!test")
public class NexxauthConfigValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NexxauthConfigValidator.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Value("${nexxauth.base-url:}")
    private String baseUrl;

    @Value("${nexxauth.public-key:}")
    private String publicKey;

    @Value("${nexxauth.client-id:}")
    private String clientId;

    @Value("${nexxauth.client-token:}")
    private String clientToken;

    @Override
    public void afterPropertiesSet() {
        log.info("─────────────────────────────────────────────────────────────");
        log.info("  Nexxauth Configuration Status");
        log.info("─────────────────────────────────────────────────────────────");

        List<String> missing = new ArrayList<>();

        logProperty("base-url", baseUrl, false, missing);
        logProperty("public-key", publicKey, true, missing);
        logProperty("client-id", clientId, false, missing);
        logProperty("client-token", clientToken, true, missing);

        if (!missing.isEmpty()) {
            log.error("─────────────────────────────────────────────────────────────");
            log.error("  STARTUP FAILED: Missing Nexxauth configuration");
            log.error("  The following required properties are not set:");
            for (String prop : missing) {
                log.error("    - nexxauth.{} (env: NEXXAUTH_{})", prop, prop.toUpperCase().replace("-", "_"));
            }
            log.error("─────────────────────────────────────────────────────────────");
            throw new IllegalStateException(
                    "Nexxauth is not configured — the application cannot start without it. "
                    + "Missing properties: " + String.join(", ", missing));
        }

        // Organisation is resolved from X-Client-Id — use the SERVER client
        // to discover the org base URL for validation.
        String orgBaseUrl = baseUrl.replaceAll("/+$", "") + "/organisations";
        URI rolesUri = URI.create(orgBaseUrl + "/roles");

        // ── Check 1: authenticated roles endpoint (validates client credentials + org access) ──
        log.info("  Checking SERVER client credentials (roles endpoint: {})...", rolesUri);

        HttpRequest rolesRequest = HttpRequest.newBuilder()
                .uri(rolesUri)
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer " + clientToken)
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(8))
                .GET()
                .build();

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> rolesResponse = httpClient.send(rolesRequest, HttpResponse.BodyHandlers.ofString());
                if (rolesResponse.statusCode() == 200) {
                    log.info("  [OK]      SERVER client authenticated — roles endpoint accessible (attempt {}/{})",
                            attempt, MAX_ATTEMPTS);
                    lastException = null;
                    break;
                } else if (rolesResponse.statusCode() == 401) {
                    fail("SERVER client authentication failed (HTTP 401): invalid client-id or client-token",
                            "Check NEXXAUTH_CLIENT_ID and NEXXAUTH_CLIENT_TOKEN — the token may have been rotated");
                } else if (rolesResponse.statusCode() == 403) {
                    fail("SERVER client has no access to its organisation (HTTP 403)",
                            "Check NEXXAUTH_CLIENT_ID — this client may belong to a different organisation");
                } else if (rolesResponse.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                    log.warn("  [RETRY]   Roles endpoint returned HTTP {} (attempt {}/{}) — retrying...",
                            rolesResponse.statusCode(), attempt, MAX_ATTEMPTS);
                    Thread.sleep(1000L * attempt);
                } else {
                    fail("Roles endpoint returned HTTP " + rolesResponse.statusCode(),
                            "Response: " + rolesResponse.body());
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("  [RETRY]   Cannot reach roles endpoint (attempt {}/{}): {} — retrying in {}s...",
                            attempt, MAX_ATTEMPTS, formatException(e), attempt);
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (lastException != null) {
            log.error("Failed to reach roles endpoint after {} attempts: {}", MAX_ATTEMPTS, formatException(lastException), lastException);
            fail("Cannot reach roles endpoint (" + rolesUri + ") after " + MAX_ATTEMPTS + " attempts: "
                    + formatException(lastException),
                    "Verify network/DNS connectivity and firewall settings for " + baseUrl);
        }

        log.info("─────────────────────────────────────────────────────────────");
        log.info("  Nexxauth configuration OK — all checks passed");
        log.info("─────────────────────────────────────────────────────────────");
    }

    private static String formatException(Throwable t) {
        if (t == null) return "unknown error";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            sb.append(": ").append(t.getMessage());
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(" (cause: ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private void fail(String error, String hint) {
        log.error("─────────────────────────────────────────────────────────────");
        log.error("  STARTUP FAILED: Nexxauth is not operational");
        log.error("  Error: {}", error);
        if (hint != null) {
            log.error("  Hint:   {}", hint);
        }
        log.error("─────────────────────────────────────────────────────────────");
        throw new IllegalStateException("Nexxauth startup validation failed: " + error);
    }

    private void logProperty(String name, String value, boolean mask, List<String> missing) {
        if (value == null || value.isBlank()) {
            log.warn("  [MISSING] nexxauth.{}", name);
            missing.add(name);
        } else if (mask) {
            log.info("  [SET]     nexxauth.{} = {}...{}", name, value.substring(0, Math.min(4, value.length())), "*".repeat(Math.max(0, value.length() - 4)));
        } else {
            log.info("  [SET]     nexxauth.{} = {}", name, value);
        }
    }
}
