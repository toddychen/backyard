package com.backyard.notification.common.cassandra.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Cassandra entity for the push_destinations_by_user table.
 *
 * A PushDestination is a unique push token belonging to a user. The push token
 * itself is the unique identifier — APNS and FCM both guarantee that a token
 * uniquely identifies one app installation.
 *
 * Partition key : user_id — one partition per user Clustering key: push_token —
 * uniqueness enforced by primary key
 *
 * Token rotation: push_token is immutable once written (Cassandra PK). When a
 * token rotates, delete the old row and insert a new one. Include user_id in
 * send job messages so senders can write token invalidations directly without a
 * reverse lookup.
 *
 * Schema source of truth: infra/cassandra/schema.cql
 */
@Table("push_destinations_by_user")
public class PushDestination {

    /** Partition key — one partition per user. */
    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    /**
     * Clustering key — globally unique push token. APNS: hex string up to 256
     * chars. FCM: registration token up to 4096 chars.
     */
    @PrimaryKeyColumn(name = "push_token", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    private String pushToken;

    /** 'APNS' or 'FCM'. */
    @Column("platform")
    private String platform;

    /**
     * APNS only: target app bundle ID (e.g. com.example.app). Null for FCM
     * destinations.
     */
    @Column("bundle_id")
    private String bundleId;

    /**
     * APNS only: 'SANDBOX' or 'PRODUCTION'. Null for FCM destinations.
     */
    @Column("apns_env")
    private String apnsEnv;

    /** BCP-47 locale tag (e.g. en-US). Used for localised notification content. */
    @Column("locale")
    private String locale;

    /** False when user has disabled notifications in OS settings. */
    @Column("notifications_enabled")
    private boolean notificationsEnabled;

    /**
     * False when APNS returns 410 (Unregistered) or FCM returns UNREGISTERED. No
     * further sends attempted until token refreshes.
     */
    @Column("token_valid")
    private boolean tokenValid;

    /** When the push token was first registered or last refreshed. */
    @Column("registered_at")
    private Instant registeredAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPushToken() {
        return pushToken;
    }

    public void setPushToken(String pushToken) {
        this.pushToken = pushToken;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getApnsEnv() {
        return apnsEnv;
    }

    public void setApnsEnv(String apnsEnv) {
        this.apnsEnv = apnsEnv;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    public boolean isTokenValid() {
        return tokenValid;
    }

    public void setTokenValid(boolean tokenValid) {
        this.tokenValid = tokenValid;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
