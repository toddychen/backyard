package com.backyard.playground.data.persist.mysql.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents one refresh token in a token family.
 *
 * <p>
 * {@code familyId} groups all tokens issued from the same login. If a revoked
 * token is presented, the whole family is revoked (theft detection).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * SHA-256 hash of the raw token sent to the client — we never store the raw
     * token.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * Groups all tokens issued from the same login session. Revoking the family
     * forces re-login.
     */
    @Column(name = "family_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
    }

    public RefreshToken(User user, String tokenHash, UUID familyId, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke() {
        this.revoked = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
