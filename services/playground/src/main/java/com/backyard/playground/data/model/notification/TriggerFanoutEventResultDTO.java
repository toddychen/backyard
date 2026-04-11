package com.backyard.playground.data.model.notification;

import java.util.UUID;

/** Result returned after fan-out trigger messages are enqueued. */
public record TriggerFanoutEventResultDTO(
        UUID eventId,
        String topicId,
        int fanoutMessages) {
}
