package com.backyard.notification.common.kafka;

/** Kafka topic name constants shared across all notification services. */
public final class Topics {

    /** Fan-out jobs: one message per token range segment per event. */
    public static final String NOTIFICATION_FANOUT  = "notification.fanout";

    /** Resolve jobs: batch of user_ids to look up push destinations. */
    public static final String NOTIFICATION_RESOLVE = "notification.resolve";

    /** Send jobs: per-platform batches of push destinations ready to send. */
    public static final String NOTIFICATION_SEND    = "notification.send";

    /** Retry queue: transient send failures, one attempt then DLT. */
    public static final String NOTIFICATION_RETRY   = "notification.retry";

    // notification.retry-dlq — auto-created by @RetryableTopic in RetryConsumer

    private Topics() {}
}
