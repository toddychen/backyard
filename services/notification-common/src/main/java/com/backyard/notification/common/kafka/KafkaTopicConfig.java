package com.backyard.notification.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Defines all Kafka topics for the notification pipeline.
 *
 * <p>Topics are created on startup by whichever service starts first.
 * Creation is idempotent — existing topics are left unchanged.
 *
 * <p>Pipeline: detector → notification.fanout → notification.resolve →
 * notification.send → notification.retry → notification.retry-dlq
 *
 * <p>{@code notification.retry-dlq} is auto-created by
 * {@code @RetryableTopic} in RetryConsumer — not declared here.
 *
 * <p>Partition counts: fanout/resolve/send: 16 (2 pods × 4 threads = 8
 * consumers, 2 partitions each, headroom to scale to 16); retry: 8.
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * Fan-out jobs published by the detector: N=20 messages per event, one per
     * Cassandra token range. Key: rangeIndex % 16.
     */
    @Bean
    public NewTopic notificationFanout() {
        return TopicBuilder.name(Topics.NOTIFICATION_FANOUT)
                .partitions(16).replicas(1)
                .config("retention.ms", "3600000") // 1 hour
                .config("segment.bytes", "10485760") // 10MB — avoids 1GB pre-allocation in dev
                .build();
    }

    /**
     * Resolve jobs: batches of up to 400 user_ids to look up push destinations.
     * Key: rangeIndex % 16.
     */
    @Bean
    public NewTopic notificationResolve() {
        return TopicBuilder.name(Topics.NOTIFICATION_RESOLVE)
                .partitions(16).replicas(1)
                .config("retention.ms", "3600000") // 1 hour
                .config("segment.bytes", "10485760") // 10MB — avoids 1GB pre-allocation in dev
                .build();
    }

    /**
     * Send jobs: per-platform batches of push destinations. Key: push_token of
     * first device.
     */
    @Bean
    public NewTopic notificationSend() {
        return TopicBuilder.name(Topics.NOTIFICATION_SEND)
                .partitions(16).replicas(1)
                .config("retention.ms", "3600000") // 1 hour
                .config("segment.bytes", "10485760") // 10MB — avoids 1GB pre-allocation in dev
                .build();
    }

    /** Retry queue: transient failures with attempt counter. */
    @Bean
    public NewTopic notificationRetry() {
        return TopicBuilder.name(Topics.NOTIFICATION_RETRY)
                .partitions(8).replicas(1)
                .config("retention.ms", "3600000") // 1 hour
                .config("segment.bytes", "10485760") // 10MB — avoids 1GB pre-allocation in dev
                .build();
    }

}
