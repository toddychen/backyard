package com.backyard.notification.common.cassandra.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Cassandra entity for the subscriptions table.
 *
 * Partition key : user_id  — billions of small partitions, one per user
 * Clustering key: topic_id — O(1) lookup per topic within a user partition
 *
 * Rows are hard-deleted on unsubscribe or expiry. No soft-delete flag —
 * presence of a row means the subscription is active. Cassandra tombstones
 * are short-lived and cleaned by compaction after gc_grace_seconds (10d).
 *
 * Fan-out reads use the SAI index on topic_id with token range filtering;
 * user-facing reads hit the partition key directly — no index needed.
 *
 * Schema source of truth: infra/cassandra/schema.cql
 */
@Table("subscriptions")
public class Subscription {

    /** Partition key — one partition per user, ~20 rows on average. */
    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    /**
     * Clustering key — topic identifier string (e.g. "sports").
     * No pre-registration required; any string is a valid topic.
     * SAI index on this column enables cross-partition fan-out reads.
     */
    @PrimaryKeyColumn(name = "topic_id", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    private String topicId;

    @Column("subscribed_at")
    private Instant subscribedAt;

    /**
     * When this subscription expires. Null means no expiration —
     * the subscription remains active until explicitly deleted.
     *
     * Transient subscriptions (e.g. a sport game topic that expires
     * at game end) should set this field. A background cleanup task
     * periodically hard-deletes rows where expires_at < now().
     *
     * Note: Cassandra's native TTL could auto-delete rows at expiry
     * without a cleanup task, but we prefer explicit control via this
     * field so expiry times are queryable and cleanup is observable.
     */
    @Column("expires_at")
    private Instant expiresAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public Instant getSubscribedAt() {
        return subscribedAt;
    }

    public void setSubscribedAt(Instant subscribedAt) {
        this.subscribedAt = subscribedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
