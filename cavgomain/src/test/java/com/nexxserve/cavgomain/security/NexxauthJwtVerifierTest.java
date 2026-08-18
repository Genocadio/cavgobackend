package com.nexxserve.cavgomain.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexxauthJwtVerifierTest {

    private static PrivateKey privateKey;
    private static String publicKeyB64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();
        privateKey = keyPair.getPrivate();
        byte[] encoded = keyPair.getPublic().getEncoded();
        // Strip the X509 header to get raw DER SPKI — but NexxauthJwtVerifier
        // expects the full X509EncodedKeySpec bytes, just base64-encoded
        publicKeyB64 = Base64.getEncoder().encodeToString(encoded);
    }

    private String mintToken(Long userId, Long orgId, List<String> roles) {
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuer("nexxauth")
                .claim("type", "org-access")
                .claim("orgId", orgId)
                .claim("orgSlug", "test-org")
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();
    }

    @Test
    void verify_validToken_returnsClaims() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = mintToken(42L, 1L, List.of("admin", "driver"));

        var claims = verifier.verify(token);

        assertEquals(42L, claims.userId());
        assertEquals(1L, claims.orgId());
        assertEquals("test-org", claims.orgSlug());
        assertEquals(List.of("admin", "driver"), claims.roles());
        assertNotNull(claims.issuedAt());
        assertNotNull(claims.expiresAt());
    }

    @Test
    void verify_wrongIssuer_throws() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = Jwts.builder()
                .setSubject("1")
                .setIssuer("wrong-issuer")
                .claim("type", "org-access")
                .claim("orgId", 1L)
                .claim("roles", List.of())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        var ex = assertThrows(JwtAuthenticationException.class, () -> verifier.verify(token));
        assertEquals(JwtAuthenticationException.Reason.INVALID_TOKEN, ex.getReason());
    }

    @Test
    void verify_wrongTokenType_throws() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = Jwts.builder()
                .setSubject("1")
                .setIssuer("nexxauth")
                .claim("type", "user-access")  // wrong type
                .claim("orgId", 1L)
                .claim("roles", List.of())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        var ex = assertThrows(JwtAuthenticationException.class, () -> verifier.verify(token));
        assertEquals(JwtAuthenticationException.Reason.INVALID_TOKEN, ex.getReason());
    }

    @Test
    void verify_expiredToken_throws() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = Jwts.builder()
                .setSubject("1")
                .setIssuer("nexxauth")
                .claim("type", "org-access")
                .claim("orgId", 1L)
                .claim("roles", List.of())
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200_000))
                .setExpiration(new Date(System.currentTimeMillis() - 3600_000))
                .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        var ex = assertThrows(JwtAuthenticationException.class, () -> verifier.verify(token));
        assertEquals(JwtAuthenticationException.Reason.EXPIRED_TOKEN, ex.getReason());
    }

    @Test
    void verify_tamperedSignature_throws() throws Exception {
        // Generate a different key pair to simulate wrong signer
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PrivateKey otherKey = gen.generateKeyPair().getPrivate();

        String token = Jwts.builder()
                .setSubject("1")
                .setIssuer("nexxauth")
                .claim("type", "org-access")
                .claim("orgId", 1L)
                .claim("roles", List.of())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(otherKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        var ex = assertThrows(JwtAuthenticationException.class, () -> verifier.verify(token));
        assertEquals(JwtAuthenticationException.Reason.INVALID_TOKEN, ex.getReason());
    }

    @Test
    void verify_emptyRoles_returnsEmptyList() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = mintToken(1L, 1L, List.of());

        var claims = verifier.verify(token);
        assertTrue(claims.roles().isEmpty());
    }

    @Test
    void verify_nullRoles_returnsEmptyList() {
        var verifier = new NexxauthJwtVerifier(publicKeyB64);
        String token = Jwts.builder()
                .setSubject("1")
                .setIssuer("nexxauth")
                .claim("type", "org-access")
                .claim("orgId", 1L)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        var claims = verifier.verify(token);
        assertTrue(claims.roles().isEmpty());
    }
}
