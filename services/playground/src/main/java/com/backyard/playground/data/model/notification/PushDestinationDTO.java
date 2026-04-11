package com.backyard.playground.data.model.notification;

import java.time.Instant;
import java.util.UUID;

import com.backyard.notification.common.cassandra.entity.PushDestination;

/** Read model returned by the push-destination endpoints. */
public record PushDestinationDTO(
        UUID userId,
        String pushToken,
        String platform,
        String bundleId,
        String apnsEnv,
        String locale,
        boolean notificationsEnabled,
        boolean tokenValid,
        Instant registeredAt,
        Instant updatedAt) {

    public static PushDestinationDTO from(PushDestination e) {
        return new PushDestinationDTO(
                e.getUserId(),
                e.getPushToken(),
                e.getPlatform(),
                e.getBundleId(),
                e.getApnsEnv(),
                e.getLocale(),
                e.isNotificationsEnabled(),
                e.isTokenValid(),
                e.getRegisteredAt(),
                e.getUpdatedAt());
    }
}
