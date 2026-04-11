package com.backyard.notification.consumer.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.SendMessage;
import com.backyard.notification.consumer.service.NotificationSenderService;

/**
 * Consumes {@code notification.send} messages.
 *
 * <p>Each message is platform-homogeneous (APNS or FCM only — split upstream
 * by ResolveConsumer). APNS destinations are sent one token per HTTP/2 call;
 * FCM destinations are sent as a single batch call.
 *
 * <p>On transient failure the service publishes to {@code notification.retry}.
 * On token-invalid it logs and discards.
 */
@Component
public class SendConsumer {

    private static final Logger log = LoggerFactory.getLogger(SendConsumer.class);
    private final NotificationSenderService senderService;

    public SendConsumer(NotificationSenderService senderService) {
        this.senderService = senderService;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_SEND)
    public void consume(ConsumerRecord<String, SendMessage> record) {
        var msg = record.value();
        log.debug("Send received: eventId={} destinations={}", msg.getEventId(), msg.getDestinations().size());

        var firstDest = msg.getDestinations().get(0);
        if ("APNS".equals(firstDest.getPlatform())) {
            senderService.sendApnsWithRetry(msg);
        } else {
            senderService.sendFcmBatchWithRetry(msg);
        }
    }
}
