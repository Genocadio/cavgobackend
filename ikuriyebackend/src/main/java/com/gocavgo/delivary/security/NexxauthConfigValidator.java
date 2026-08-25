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
 *   <li>Making a live API call to {@code GET /organisations/{id}/keys} (public)
 *       — validates base-url and organisation-id</li>
 *   <li>Making a live API call to {@code GET /organisations/{id}/roles}
 *       (SERVER client auth) — validates client-id, client-token, and that the
 *       client belongs to the configured organisation</li>
 * </ol>
 * If any check fails the application refuses to start.
 */
@Component
public class NexxauthConfigValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NexxauthConfigValidator.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Value("${nexxauth.base-url:}")
    private String baseUrl;

    @Value("${nexxauth.organisation-id:}")
    private String organisationId;

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
        logProperty("organisation-id", organisationId, false, missing);
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

        String orgBaseUrl = baseUrl.replaceAll("/+$", "") + "/organisations/" + organisationId;

        // ── Check 1: public keys endpoint (validates base-url + org-id) ──────
        log.info("  Checking org keys endpoint (public)...");
        try {
            HttpRequest keysRequest = HttpRequest.newBuilder()
                    .uri(URI.create(orgBaseUrl + "/keys"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> keysResponse = httpClient.send(keysRequest, HttpResponse.BodyHandlers.ofString());
            if (keysResponse.statusCode() == 200) {
                log.info("  [OK]      Organisation {} exists and keys endpoint reachable", organisationId);
            } else {
                fail("Organisation keys endpoint returned HTTP " + keysResponse.statusCode(),
                        "Check NEXXAUTH_ORGANISATION_ID (" + organisationId + ") — the org may not exist or the base URL may be wrong");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            fail("Cannot reach Nexxauth at " + orgBaseUrl + "/keys: " + e.getMessage(),
                    "Check NEXXAUTH_BASE_URL (" + baseUrl + ") — is the server reachable?");
        }

        // ── Check 2: authenticated roles endpoint (validates client credentials + org access) ──
        log.info("  Checking SERVER client credentials (roles endpoint)...");
        try {
            HttpRequest rolesRequest = HttpRequest.newBuilder()
                    .uri(URI.create(orgBaseUrl + "/roles"))
                    .header("Authorization", "Bearer " + clientToken)
                    .header("X-Client-Id", clientId)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> rolesResponse = httpClient.send(rolesRequest, HttpResponse.BodyHandlers.ofString());
            if (rolesResponse.statusCode() == 200) {
                log.info("  [OK]      SERVER client authenticated — roles endpoint accessible");
            } else if (rolesResponse.statusCode() == 401) {
                fail("SERVER client authentication failed (HTTP 401): invalid client-id or client-token",
                        "Check NEXXAUTH_CLIENT_ID and NEXXAUTH_CLIENT_TOKEN — the token may have been rotated");
            } else if (rolesResponse.statusCode() == 403) {
                fail("SERVER client has no access to organisation " + organisationId + " (HTTP 403)",
                        "Check NEXXAUTH_ORGANISATION_ID (" + organisationId + ") — this client belongs to a different organisation");
            } else {
                fail("Roles endpoint returned HTTP " + rolesResponse.statusCode(),
                        "Response: " + rolesResponse.body());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            fail("Cannot reach roles endpoint: " + e.getMessage(), null);
        }

        log.info("─────────────────────────────────────────────────────────────");
        log.info("  Nexxauth configuration OK — all checks passed");
        log.info("─────────────────────────────────────────────────────────────");
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
