package com.backyard.playground.data.model.notification;

import java.time.Instant;
import java.util.UUID;

import com.backyard.notification.common.cassandra.entity.Subscription;

/** Read model returned by the subscription endpoints. */
public record SubscriptionDTO(
        UUID userId,
        String topicId,
        Instant subscribedAt,
        Instant expiresAt) {

    public static SubscriptionDTO from(Subscription e) {
        return new SubscriptionDTO(
                e.getUserId(),
                e.getTopicId(),
                e.getSubscribedAt(),
                e.getExpiresAt());
    }
}
