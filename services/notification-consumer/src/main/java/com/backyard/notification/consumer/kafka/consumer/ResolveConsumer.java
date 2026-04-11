package com.backyard.notification.consumer.kafka.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.PushDestinationInfo;
import com.backyard.notification.common.kafka.message.ResolveMessage;
import com.backyard.notification.common.kafka.message.SendMessage;
import com.backyard.notification.consumer.kafka.producer.SendProducer;
import com.backyard.notification.consumer.service.NotificationDataService;

/**
 * Consumes {@code notification.resolve} messages.
 *
 * <p>
 * Each message carries up to 1000 user_ids. This consumer fires one async
 * Cassandra read per user_id (all in parallel), filters for deliverable
 * destinations, then splits into platform batches and publishes to
 * {@code notification.send}.
 *
 * <p>
 * Batch size limits: APNS: 200 tokens per message (one HTTP/2 call each,
 * parallelised by the sender). FCM: 500 tokens per message (FCM batch API).
 */
@Component
public class ResolveConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResolveConsumer.class);
    private static final int APNS_BATCH_SIZE = 200;
    private static final int FCM_BATCH_SIZE = 500;

    private final NotificationDataService notificationDataService;
    private final SendProducer sendProducer;

    public ResolveConsumer(
            NotificationDataService notificationDataService,
            SendProducer sendProducer) {
        this.notificationDataService = notificationDataService;
        this.sendProducer = sendProducer;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_RESOLVE)
    public void consume(ConsumerRecord<String, ResolveMessage> record) {
        var msg = record.value();
        log.debug("Resolve received: eventId={} users={}", msg.getEventId(), msg.getUserIds().size());

        // Fire all device reads concurrently then collect
        List<PushDestinationInfo> destinations = notificationDataService
                .resolveDestinations(msg.getUserIds())
                .stream()
                .filter(d -> d.getPushToken() != null)
                .toList();

        if (destinations.isEmpty()) {
            log.debug("Resolve: no deliverable destinations for eventId={}", msg.getEventId());
            return;
        }

        List<PushDestinationInfo> apns = destinations.stream()
                .filter(d -> "APNS".equals(d.getPlatform()))
                .toList();
        List<PushDestinationInfo> fcm = destinations.stream()
                .filter(d -> "FCM".equals(d.getPlatform()))
                .toList();

        publishBatches(msg, apns, APNS_BATCH_SIZE);
        publishBatches(msg, fcm, FCM_BATCH_SIZE);

        log.info("Resolve done: eventId={} apns={} fcm={}", msg.getEventId(), apns.size(), fcm.size());
    }

    private void publishBatches(ResolveMessage src, List<PushDestinationInfo> destinations, int batchSize) {
        for (int i = 0; i < destinations.size(); i += batchSize) {
            var batch = destinations.subList(i, Math.min(i + batchSize, destinations.size()));
            sendProducer.publish(toSendMessage(src, batch));
        }
    }

    private SendMessage toSendMessage(ResolveMessage src, List<PushDestinationInfo> destinations) {
        var msg = new SendMessage();
        msg.setEventId(src.getEventId());
        msg.setTitle(src.getTitle());
        msg.setBody(src.getBody());
        msg.setData(src.getData());
        msg.setDestinations(new ArrayList<>(destinations));
        return msg;
    }
}
