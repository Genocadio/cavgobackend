package com.nexxserve.cavgomain.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexxauthWebhookVerifierTest {

    @Test
    void acceptsValidSignedWebhookAndRejectsReplay() throws Exception {
        KeyPair pair = generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        NexxauthWebhookVerifier verifier = new NexxauthWebhookVerifier(publicKey, "5");

        String token = token(pair, "org-webhook", "USER_SESSION_REVOKED", "jti-1");
        NexxauthWebhookVerifier.WebhookClaims claims = verifier.verify("Bearer " + token);

        assertEquals(5L, claims.userId());
        assertEquals(5L, claims.orgId());
        assertEquals("USER_SESSION_REVOKED", claims.event());
        assertEquals("LOGOUT", claims.reason());

        assertThrows(JwtAuthenticationException.class, () -> verifier.verify("Bearer " + token));
    }

    @Test
    void rejectsAccessTokenType() throws Exception {
        KeyPair pair = generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        NexxauthWebhookVerifier verifier = new NexxauthWebhookVerifier(publicKey, "5");

        String accessToken = token(pair, "org-access", "USER_SIGNED_IN", "jti-2");
        assertThrows(JwtAuthenticationException.class, () -> verifier.verify("Bearer " + accessToken));
    }

    @Test
    void rejectsUnexpectedOrganisation() throws Exception {
        KeyPair pair = generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        NexxauthWebhookVerifier verifier = new NexxauthWebhookVerifier(publicKey, "5");

        String token = token(pair, "org-webhook", "USER_SIGNED_IN", "jti-3");
        NexxauthWebhookVerifier otherOrgVerifier = new NexxauthWebhookVerifier(publicKey, "6");
        assertThrows(JwtAuthenticationException.class, () -> otherOrgVerifier.verify("Bearer " + token));
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String token(KeyPair pair, String type, String event, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject("5")
                .setIssuer("nexxauth")
                .claim("type", type)
                .claim("orgId", 5L)
                .claim("orgSlug", "cavgobackend")
                .claim("event", event)
                .claim("reason", "LOGOUT")
                .setId(jti)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(60)))
                .signWith(pair.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }
}
