package com.nexxserve.cavgomain.security;

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
 * organisation's RSA key) offline against the organisation's public key,
 * configured at deploy time via {@code nexxauth.public-key}
 * ({@code NEXXAUTH_PUBLIC_KEY}). No runtime fetch is made.
 *
 * <p>Verification requires: the RS256 signature must be valid against the
 * configured key, {@code iss == "nexxauth"} and {@code type == "org-access"}.
 * Roles are read from the token's {@code roles} claim.
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
                    "Invalid nexxauth.public-key — expected the base64 DER SPKI publicKey value from GET /organisations/{organisationId}/keys",
                    e);
        }
    }

    public NexxauthClaims verify(String token) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token);

            Claims claims = jws.getBody();
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

            return new NexxauthClaims(
                    userId,
                    orgId,
                    orgSlug,
                    roles == null ? List.of() : List.copyOf(roles),
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
     * Claims extracted from a verified org-access token.
     */
    public record NexxauthClaims(
            Long userId,
            Long orgId,
            String orgSlug,
            List<String> roles,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
