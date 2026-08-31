package com.gocavgo.delivary.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Verifies Nexxauth organisation access tokens (RS256 JWTs signed with the
 * organisation's RSA key) offline against the organisation's **public key**,
 * configured at deploy time via {@code nexxauth.public-key}
 * ({@code NEXXAUTH_PUBLIC_KEY}) — the {@code publicKey} value (base64 DER SPKI,
 * no PEM headers) served by the Nexxauth org API at
 * {@code GET /organisations/keys}. No runtime fetch is made.
 *
 * <p>Verification requires: the RS256 signature must be valid against the
 * configured key, {@code iss == "nexxauth"} and {@code type == "org-access"}.
 * Roles are read from the token's {@code roles} claim — permissions are
 * resolved server-side by Nexxauth, never shipped in the token.
 *
 * <p><b>Key rotation:</b> because the key is baked in rather than fetched,
 * rotating the organisation's key (or the platform's) requires updating
 * {@code NEXXAUTH_PUBLIC_KEY} and restarting. Retired-key grace until token
 * expiry is up to the operator: tokens signed with the previous key fail once
 * the new key is deployed.
 */
@Component
public class NexxauthJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(NexxauthJwtVerifier.class);

    private final PublicKey publicKey;

    public NexxauthJwtVerifier(@Value("${nexxauth.public-key}") String publicKeyB64) {
        if (publicKeyB64 == null || publicKeyB64.isBlank()) {
            throw new IllegalStateException(
                    "nexxauth.public-key is not configured (NEXXAUTH_PUBLIC_KEY) — the backend cannot verify org-access tokens without it");
        }
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyB64.trim());
            var keySpec = new X509EncodedKeySpec(der);
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
            log.info("Nexxauth org public key loaded from configuration");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid nexxauth.public-key — expected the base64 DER SPKI `publicKey` value from GET /organisations/keys",
                    e);
        }
    }

    public NexxauthClaims verify(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();
            if (!"nexxauth".equals(claims.getIssuer())) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN,
                        "Unexpected token issuer: " + claims.getIssuer()
                );
            }
            if (!"org-access".equals(claims.get("type", String.class))) {
                throw new JwtAuthenticationException(
                        JwtAuthenticationException.Reason.INVALID_TOKEN,
                        "Token is not an org-access token"
                );
            }

            Long userId = Long.valueOf(claims.getSubject());
            Long orgId = claims.get("orgId", Long.class);
            String orgSlug = claims.get("orgSlug", String.class);
            List<String> roles = claims.get("roles", List.class);
            String dataHash = claims.get("dataHash", String.class);

            return new NexxauthClaims(
                    userId,
                    orgId,
                    orgSlug,
                    roles == null ? List.of() : List.copyOf(roles),
                    dataHash,
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.EXPIRED_TOKEN, "Token has expired", e);
        } catch (MalformedJwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.MALFORMED_TOKEN, "Malformed JWT token", e);
        } catch (SignatureException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID_TOKEN, "Invalid token signature", e);
        } catch (JwtAuthenticationException e) {
            throw e;
        } catch (JwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID_TOKEN, "Invalid token: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID_TOKEN, "Invalid token: " + e.getMessage(), e);
        }
    }

    /**
     * Claims extracted from a verified org-access token. {@code sub} is the
     * Nexxauth org-user id — the same value stored in the local {@code users.id}.
     * {@code dataHash} is an opaque UUID that changes on every non-password
     * user mutation — the backend compares it to the stored hash to detect
     * stale user data without hitting Nexxauth on every request.
     */
    public record NexxauthClaims(
            Long userId,
            Long orgId,
            String orgSlug,
            List<String> roles,
            String dataHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
