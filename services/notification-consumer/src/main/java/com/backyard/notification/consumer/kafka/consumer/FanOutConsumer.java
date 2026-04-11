package com.backyard.notification.consumer.kafka.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.FanoutMessage;
import com.backyard.notification.common.kafka.message.ResolveMessage;
import com.backyard.notification.consumer.kafka.producer.ResolveProducer;
import com.backyard.notification.consumer.service.NotificationDataService;

/**
 * Consumes {@code notification.fanout} messages.
 *
 * <p>
 * Each message covers one Cassandra token range segment. This consumer pages
 * through all subscriptions for the given topic_id within that range, batching
 * user_ids into groups of up to {@value #USER_BATCH_SIZE} and publishing each
 * batch to {@code notification.resolve} for async device resolution.
 *
 * <p>
 * If the consumer fails mid-way, Kafka replays the message and the range is
 * re-scanned from the beginning. CQL reads and Kafka publishes are both
 * idempotent so duplicate resolve messages are safe.
 */
@Component
public class FanOutConsumer {

    private static final Logger log = LoggerFactory.getLogger(FanOutConsumer.class);
    private static final int USER_BATCH_SIZE = 400;

    private final NotificationDataService notificationDataService;
    private final ResolveProducer resolveProducer;

    public FanOutConsumer(NotificationDataService notificationDataService, ResolveProducer resolveProducer) {
        this.notificationDataService = notificationDataService;
        this.resolveProducer = resolveProducer;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_FANOUT)
    public void consume(ConsumerRecord<String, FanoutMessage> record) {
        var msg = record.value();
        log.info("FanOut received: topic={} eventId={} range=[{}, {})",
                msg.getTopicId(), msg.getEventId(), msg.getRangeStart(), msg.getRangeEnd());

        List<UUID> batch = new ArrayList<>(USER_BATCH_SIZE);
        int totalUsers = 0;
        int totalBatches = 0;

        for (UUID userId : notificationDataService.streamSubscribers(msg.getTopicId(), msg.getRangeStart(),
                msg.getRangeEnd())) {
            batch.add(userId);
            if (batch.size() == USER_BATCH_SIZE) {
                resolveProducer.publish(toResolveMessage(msg, batch));
                totalBatches++;
                totalUsers += batch.size();
                batch = new ArrayList<>(USER_BATCH_SIZE);
            }
        }

        // Publish remaining partial batch
        if (!batch.isEmpty()) {
            resolveProducer.publish(toResolveMessage(msg, batch));
            totalBatches++;
            totalUsers += batch.size();
        }

        log.info("FanOut done: topic={} eventId={} users={} batches={}",
                msg.getTopicId(), msg.getEventId(), totalUsers, totalBatches);
    }

    private ResolveMessage toResolveMessage(FanoutMessage src, List<UUID> userIds) {
        var msg = new ResolveMessage();
        msg.setTopicId(src.getTopicId());
        msg.setEventId(src.getEventId());
        msg.setTitle(src.getTitle());
        msg.setBody(src.getBody());
        msg.setData(src.getData());
        msg.setUserIds(new ArrayList<>(userIds));
        return msg;
    }
}
