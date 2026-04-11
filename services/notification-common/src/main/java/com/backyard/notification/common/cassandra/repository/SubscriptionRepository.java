package com.backyard.notification.common.cassandra.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.cassandra.repository.CassandraRepository;

import com.backyard.notification.common.cassandra.entity.Subscription;

/**
 * Spring Data Cassandra repository for the subscriptions table.
 *
 * Rows are hard-deleted on unsubscribe — presence of a row means the
 * subscription is active. No active flag needed.
 *
 * User-facing queries hit the partition key (user_id) directly. Fan-out queries
 * use the SAI index on topic_id combined with token() range filtering — handled
 * via the driver session directly in FanOutService because Spring Data
 * Cassandra does not support token() range predicates in derived query methods.
 */
public interface SubscriptionRepository extends CassandraRepository<Subscription, UUID> {

    /** All subscriptions for a user. Single-partition read, no index. */
    List<Subscription> findByUserId(UUID userId);

    /** Check a specific subscription. Single-partition point lookup. */
    Optional<Subscription> findByUserIdAndTopicId(UUID userId, String topicId);
}
