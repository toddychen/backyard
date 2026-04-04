package com.backyard.playground.service;
import org.springframework.context.annotation.Profile;

import com.backyard.playground.dao.AuthDao;
import com.backyard.playground.data.persist.mysql.auth.RefreshToken;
import com.backyard.playground.data.persist.mysql.auth.User;
import com.backyard.playground.exception.ConflictException;
import com.backyard.playground.exception.UnauthorizedException;
import com.backyard.playground.security.JwtAuthenticationFilter;
import com.backyard.playground.security.JwtTokenService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Authentication business logic — register, login, token refresh, and logout.
 *
 * <p>
 * Owns all business rules, validation, and exception throwing. DB work is
 * delegated to {@link AuthDao} (small, focused transactions). This class is not
 * annotated with {@code @Transactional} — transaction boundaries are owned by
 * {@link AuthDao}.
 */
@Profile("!home")
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthDao authDao;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;

    public AuthService(
            AuthDao authDao,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redis) {
        this.authDao = authDao;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
    }

    /** Register a new user. Throws {@link ConflictException} if email is taken. */
    public void register(String email, String rawPassword) {
        if (authDao.findByEmail(email).isPresent()) {
            throw new ConflictException("email already registered");
        }
        boolean saved = authDao.saveUser(new User(email, passwordEncoder.encode(rawPassword)));
        if (!saved) {
            // Concurrent registration slipped past the check above
            throw new ConflictException("email already registered");
        }
    }

    /**
     * Verify credentials and issue a new access + refresh token pair. Throws
     * {@link UnauthorizedException} if credentials are invalid.
     */
    public TokenPair login(String email, String rawPassword) {
        User user = authDao.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("invalid credentials"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("invalid credentials");
        }

        String accessToken = jwtTokenService.issueAccessToken(user.getId());
        String refreshToken = jwtTokenService.generateRawRefreshToken();
        Instant expiresAt = Instant.now().plusSeconds(jwtTokenService.getRefreshTtlSeconds());
        authDao.issueRefreshToken(user, jwtTokenService.hashRefreshToken(refreshToken), expiresAt);

        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Rotate a refresh token — revoke the old one and issue a new access + refresh
     * token pair in the same family.
     *
     * <p>
     * <b>Known concurrency issue:</b> two simultaneous requests with the same
     * refresh token may both pass the {@code isRevoked()} check before either
     * commits, resulting in two valid token pairs issued from one refresh token.
     * Fix: add {@code @Version} to {@link RefreshToken} for optimistic locking.
     */
    public TokenPair refresh(String rawRefreshToken) {
        String hash = jwtTokenService.hashRefreshToken(rawRefreshToken);
        RefreshToken stored = authDao.findRefreshToken(hash)
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));

        if (stored.isRevoked()) {
            // Reuse of a revoked token — possible theft.
            // revokeFamily commits in its own transaction before
            // the exception is thrown, so it is not rolled back.
            authDao.revokeFamily(stored.getFamilyId());
            throw new UnauthorizedException("refresh token reuse detected");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("refresh token expired");
        }

        String accessToken = jwtTokenService.issueAccessToken(stored.getUser().getId());
        String refreshToken = jwtTokenService.generateRawRefreshToken();
        Instant expiresAt = Instant.now().plusSeconds(jwtTokenService.getRefreshTtlSeconds());
        authDao.rotateRefreshToken(stored, jwtTokenService.hashRefreshToken(refreshToken), expiresAt);

        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Change a user's password. Verifies the current password, updates the hash,
     * revokes all refresh tokens (all sessions are invalidated), and denylists the
     * current access token. Throws {@link UnauthorizedException} if currentPassword
     * is wrong.
     */
    public void changePassword(UUID userId, String currentPassword, String newPassword, String accessToken) {
        User user = authDao.findById(userId).orElseThrow(() -> new UnauthorizedException("invalid credentials"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("invalid credentials");
        }

        authDao.updatePassword(userId, passwordEncoder.encode(newPassword));
        authDao.revokeAllRefreshTokensForUser(userId);
        denylistAccessToken(accessToken);
    }

    /**
     * Revoke the refresh token and denylist the access token in Redis so it cannot
     * be reused before it naturally expires.
     */
    public void logout(String rawRefreshToken, String accessToken) {
        denylistAccessToken(accessToken);
        String hash = jwtTokenService.hashRefreshToken(rawRefreshToken);
        authDao.revokeRefreshToken(hash);
    }

    // --- helpers ---

    private void denylistAccessToken(String accessToken) {
        try {
            Claims claims = jwtTokenService.parseAccessToken(accessToken);
            long remainingSecs = claims.getExpiration()
                    .toInstant()
                    .minusSeconds(Instant.now().getEpochSecond())
                    .getEpochSecond();
            if (remainingSecs > 0) {
                redis.opsForValue().set(
                        JwtAuthenticationFilter.JTI_DENYLIST_PREFIX + claims.getId(),
                        "1",
                        Duration.ofSeconds(remainingSecs));
            }
        } catch (JwtException e) {
            // parseAccessToken throws JwtException (incl.
            // ExpiredJwtException) if the token is expired,
            // has a bad signature, wrong iss/aud, or is
            // malformed. In all cases the token is already
            // unusable, so there is nothing to denylist.
            log.debug("Access token already expired, skipping denylist");
        } catch (Exception e) {
            // Redis unavailable — best-effort; refresh token
            // is already revoked so this is acceptable
            log.warn("Failed to denylist access token jti", e);
        }
    }

    /** Access + refresh token pair returned to the client. */
    public record TokenPair(String accessToken, String refreshToken) {
    }
}
