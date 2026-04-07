package com.backyard.playground.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * Issues and validates access JWTs.
 *
 * <p>
 * Access tokens are short-lived signed JWTs (HS256). The subject is the user's
 * UUID string. A {@code jti} claim is included so the token can be added to a
 * denylist on logout.
 */
@Profile("!home")
@Service
public class JwtTokenService {

    private static final String ISSUER = "playground";
    private static final String AUDIENCE = "playground";

    private final SecretKey signingKey;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtTokenService(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.access-ttl-seconds:600}") long accessTtlSeconds,
            @Value("${auth.jwt.refresh-ttl-seconds:1209600}") long refreshTtlSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** Issues a signed access JWT for the given user UUID. */
    public String issueAccessToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .audience()
                .add(AUDIENCE)
                .and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates a JWT and returns its claims.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Generates a random opaque refresh token (UUID string). */
    public String generateRawRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /** SHA-256 hash of a raw refresh token — stored in DB. */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }
}
