package com.nexxserve.cavgomain.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Verifies short-lived Nexxauth lifecycle webhook JWTs.
 *
 * These tokens are signed with the same organisation key as org-access tokens,
 * but use a different {@code type} claim so an access token cannot be replayed
 * as a webhook. Each webhook token has a single-use {@code jti} and a short TTL.
 */
@Component
public class NexxauthWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(NexxauthWebhookVerifier.class);
    private static final String TYPE_WEBHOOK = "org-webhook";
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "USER_SIGNED_IN", "USER_SESSION_REVOKED"
    );

    private static final long REPLAY_WINDOW_MS = 5 * 60_000L;

    private final PublicKey publicKey;
    private final Long expectedOrgId;
    private final ConcurrentMap<String, Long> seenIds = new ConcurrentHashMap<>();

    public NexxauthWebhookVerifier(
            @Value("${nexxauth.public-key}") String publicKeyB64,
            @Value("${nexxauth.organisation-id:}") String organisationId
    ) {
        if (publicKeyB64 == null || publicKeyB64.isBlank()) {
            throw new IllegalStateException("nexxauth.public-key is required for webhook verification");
        }
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyB64.trim());
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid nexxauth.public-key for webhook verification", e);
        }
        this.expectedOrgId = parseOrgId(organisationId);
        log.info("Nexxauth webhook verifier initialised");
    }

    public record WebhookClaims(Long userId, Long orgId, String event, String reason, String jti) {}

    public WebhookClaims verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new JwtAuthenticationException(
                    JwtAuthenticationException.Reason.INVALID_TOKEN, "Missing webhook token");
        }
        String token = authorizationHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new JwtAuthenticationException(
                    JwtAuthenticationException.Reason.INVALID_TOKEN, "Missing webhook token");
        }

        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token);
            Claims claims = jws.getBody();

            if (!"nexxauth".equals(claims.getIssuer())) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Unexpected webhook issuer");
            }
            if (!TYPE_WEBHOOK.equals(claims.get("type", String.class))) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Token is not a webhook token");
            }

            Instant now = Instant.now();
            Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(now)) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.EXPIRED_TOKEN, "Webhook token is expired");
            }
            if (issuedAt.isBefore(now.minusSeconds(120)) || issuedAt.isAfter(now.plusSeconds(30))) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Webhook token timestamp is outside the accepted window");
            }

            Long userId = parseLong(claims.getSubject());
            Long orgId = claimLong(claims, "orgId");
            String event = claims.get("event", String.class);
            String jti = claims.getId();
            if (userId == null || userId <= 0 || orgId == null || orgId <= 0) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Webhook identity claims are invalid");
            }
            if (expectedOrgId != null && !expectedOrgId.equals(orgId)) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Webhook organisation does not match");
            }
            if (event == null || !ALLOWED_EVENTS.contains(event)) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Unsupported webhook event");
            }
            if (jti == null || jti.isBlank()) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Webhook token id is required");
            }
            long nowMs = now.toEpochMilli();
            seenIds.entrySet().removeIf(entry -> entry.getValue() < nowMs - REPLAY_WINDOW_MS);
            if (seenIds.putIfAbsent(jti, nowMs) != null) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN, "Webhook token was already used");
            }

            String reason = claims.get("reason", String.class);
            return new WebhookClaims(userId, orgId, event,
                    reason == null || reason.isBlank() ? event : reason, jti);
        } catch (JwtAuthenticationException e) {
            throw e;
        } catch (JwtException e) {
            throw new JwtAuthenticationException(
                    JwtAuthenticationException.Reason.INVALID_TOKEN, "Invalid webhook token", e);
        } catch (Exception e) {
            throw new JwtAuthenticationException(
                    JwtAuthenticationException.Reason.INVALID_TOKEN, "Invalid webhook token", e);
        }
    }

    private static Long parseOrgId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("NEXXAUTH_ORGANISATION_ID is not numeric; webhook organisation check disabled");
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null || !value.matches("\\d+")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long claimLong(Claims claims, String key) {
        Object value = claims.get(key);
        if (value instanceof Number number) return number.longValue();
        return parseLong(value == null ? null : String.valueOf(value));
    }
}
