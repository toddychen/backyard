package com.backyard.notification.consumer.kafka.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.SendMessage;

/** Publishes per-platform destination batches to {@code notification.send}. */
@Component
public class SendProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SendProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Round-robin — no key, Kafka assigns partitions evenly. */
    public void publish(SendMessage msg) {
        kafkaTemplate.send(Topics.NOTIFICATION_SEND, msg);
    }
}
