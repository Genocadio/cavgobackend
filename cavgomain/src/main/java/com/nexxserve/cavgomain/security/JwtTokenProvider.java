package com.nexxserve.cavgomain.security;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateAccessToken(User user) {
        return generateToken(createClaims(user), user.getEmail(), accessTokenExpiration);
    }

    public String generateRefreshToken(User user) {
        return generateToken(createClaims(user), user.getEmail(), refreshTokenExpiration);
    }

    private Map<String, Object> createClaims(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("userType", getUserType(user));
        claims.put("isActive", user.getStatus().toString().equals("ACTIVE"));

        // Add company-specific details if it's a company user
        boolean isCompanyUser = user instanceof CompanyUser;
        claims.put("isCompanyUser", isCompanyUser);

        if (isCompanyUser) {
            CompanyUser companyUser = (CompanyUser) user;
            claims.put("companyId", companyUser.getCompany().getId());
            claims.put("companyRole", companyUser.getRole().toString());
        }

        return claims;
    }

    private String getUserType(User user) {
        return user.getClass().getSimpleName();
    }

    private String generateToken(Map<String, Object> claims, String subject, long expiration) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Boolean getIsCompanyUser(String token) {
        return extractClaim(token, claims -> claims.get("isCompanyUser", Boolean.class));
    }

    public Long getCompanyId(String token) {
        return extractClaim(token, claims -> {
            if (claims.get("companyId") != null) {
                return ((Number) claims.get("companyId")).longValue();
            }
            return null;
        });
    }

    public String getCompanyRole(String token) {
        return extractClaim(token, claims -> claims.get("companyRole", String.class));
    }

    public Boolean isUserActive(String token) {
        return extractClaim(token, claims -> claims.get("isActive", Boolean.class));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean validateToken(String token) {
        try {
            return !isTokenExpired(token) && isUserActive(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
}