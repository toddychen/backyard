package com.backyard.notification.common.cassandra.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.cassandra.repository.CassandraRepository;

import com.backyard.notification.common.cassandra.entity.PushDestination;

/**
 * Spring Data Cassandra repository for push_destinations_by_user.
 *
 * Partition key is user_id — all queries below hit a single partition. For
 * concurrent async reads across many user_ids, use the Cassandra driver session
 * directly (see PushDestinationResolverService in notification-consumer) rather
 * than calling findByUserId in a loop.
 */
public interface PushDestinationRepository extends CassandraRepository<PushDestination, UUID> {

    /** All push destinations for a user. Single-partition read, no index. */
    List<PushDestination> findByUserId(UUID userId);

    /**
     * Deliverable push destinations for a user: token valid and notifications
     * enabled. Filters applied server-side within the single user partition.
     */
    List<PushDestination> findByUserIdAndTokenValidTrueAndNotificationsEnabledTrue(UUID userId);

    /** Look up a specific push destination by token within a user partition. */
    java.util.Optional<PushDestination> findByUserIdAndPushToken(UUID userId, String pushToken);
}
