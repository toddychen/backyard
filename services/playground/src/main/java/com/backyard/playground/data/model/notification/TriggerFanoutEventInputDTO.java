package com.backyard.playground.data.model.notification;

/** Input for publishing a fan-out notification event to Kafka. */
public record TriggerFanoutEventInputDTO(
        String topicId,
        String title,
        String body) {
}
