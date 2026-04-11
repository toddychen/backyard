package com.backyard.notification.consumer.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.RetryMessage;
import com.backyard.notification.consumer.service.NotificationSenderService;

/**
 * Consumes {@code notification.retry} messages.
 *
 * <p>{@code @RetryableTopic} with {@code attempts = "1"} means one delivery
 * attempt only — no Spring-managed retry hop. On any failure the message is
 * forwarded immediately to {@code notification.retry-dlq}.
 *
 * <p>The 5-second process-after delay is enforced by Spring's internal
 * pause/resume mechanism — no manual seek or scheduler needed.
 */
@Component
public class RetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(RetryConsumer.class);

    private final NotificationSenderService senderService;

    public RetryConsumer(NotificationSenderService senderService) {
        this.senderService = senderService;
    }

    @RetryableTopic(attempts = "1", dltTopicSuffix = "-dlq")
    @KafkaListener(topics = Topics.NOTIFICATION_RETRY)
    public void consume(ConsumerRecord<String, RetryMessage> record) {
        var msg = record.value();
        log.debug("Retry received: eventId={} userId={}", msg.getEventId(), msg.getDestination().getUserId());
        senderService.sendOrThrow(msg);
    }
}
