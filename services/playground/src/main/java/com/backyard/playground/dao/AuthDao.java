package com.backyard.playground.dao;
import org.springframework.context.annotation.Profile;

import com.backyard.playground.data.persist.mysql.auth.RefreshToken;
import com.backyard.playground.data.persist.mysql.auth.RefreshTokenRepository;
import com.backyard.playground.data.persist.mysql.auth.User;
import com.backyard.playground.data.persist.mysql.auth.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * DB access layer for authentication — small, focused transactions that never
 * throw business exceptions.
 *
 * <p>
 * Each method is its own {@code REQUIRES_NEW} transaction so it commits
 * immediately when it returns, independent of any outer transaction. Business
 * logic and exceptions live in
 * {@link com.backyard.playground.service.AuthService}.
 */
@Profile("!home")
@Repository
public class AuthDao {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthDao(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Looks up a user by ID. No transaction needed — single read. */
    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    /** Looks up a user by email. No transaction needed — single read. */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Saves a new user. Returns {@code false} if the email is already taken (DB
     * unique constraint violation), so the caller can decide how to handle it
     * without a try/catch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveUser(User user) {
        try {
            userRepository.save(user);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Looks up a refresh token by its hash. No transaction needed — single read.
     */
    public Optional<RefreshToken> findRefreshToken(String hash) {
        return refreshTokenRepository.findByTokenHash(hash);
    }

    /** Revokes all tokens in a family (theft response) and commits immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId) {
        refreshTokenRepository.revokeFamily(familyId);
    }

    /**
     * Rotates a refresh token — revokes the old one and saves a new one in the same
     * family.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rotateRefreshToken(RefreshToken old, String newTokenHash, Instant expiresAt) {
        old.revoke();
        refreshTokenRepository.save(old);
        refreshTokenRepository.save(new RefreshToken(old.getUser(), newTokenHash, old.getFamilyId(), expiresAt));
    }

    /** Issues a new refresh token for a user (used at login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issueRefreshToken(User user, String tokenHash, Instant expiresAt) {
        refreshTokenRepository.save(new RefreshToken(user, tokenHash, UUID.randomUUID(), expiresAt));
    }

    /** Revokes a refresh token by hash if it exists. No-op if not found. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeRefreshToken(String hash) {
        refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.revoke();
            refreshTokenRepository.save(t);
        });
    }

    /** Updates a user's password hash. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updatePassword(UUID userId, String newHash) {
        userRepository.updatePasswordHash(userId, newHash);
    }

    /**
     * Revokes all refresh tokens for a user — called after a password change to
     * invalidate all existing sessions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllRefreshTokensForUser(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }
}
