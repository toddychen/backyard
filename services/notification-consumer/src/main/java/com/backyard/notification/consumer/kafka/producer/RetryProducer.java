package com.backyard.notification.consumer.kafka.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.RetryMessage;

/** Publishes failed send attempts to {@code notification.retry}. */
@Component
public class RetryProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public RetryProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Round-robin — no key, Kafka assigns partitions evenly. */
    public void publish(RetryMessage msg) {
        kafkaTemplate.send(Topics.NOTIFICATION_RETRY, msg);
    }
}
