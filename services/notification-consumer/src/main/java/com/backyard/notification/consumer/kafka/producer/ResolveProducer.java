package com.backyard.notification.consumer.kafka.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.ResolveMessage;

/** Publishes batches of user_ids to {@code notification.resolve}. */
@Component
public class ResolveProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ResolveProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Round-robin — no key, Kafka assigns partitions evenly. */
    public void publish(ResolveMessage msg) {
        kafkaTemplate.send(Topics.NOTIFICATION_RESOLVE, msg);
    }
}
