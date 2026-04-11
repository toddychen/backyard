package com.backyard.notification.common.kafka.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published to {@code notification.retry} on transient send failure.
 *
 * <p>RetryConsumer sleeps until {@code processAfter}, attempts one re-send,
 * then forwards to {@code notification.dlq} on any further failure.
 */
public class RetryMessage {

    /**
     * Origin event ID — for tracing. Future: use for late-binding content lookup.
     */
    private UUID eventId;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String title;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String body;
    private Map<String, String> data;
    private PushDestinationInfo destination;
    /** Earliest time this message should be processed. Set to now+5s by SendConsumer. */
    private Instant processAfter;

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }

    public PushDestinationInfo getDestination() {
        return destination;
    }

    public void setDestination(PushDestinationInfo destination) {
        this.destination = destination;
    }

    public Instant getProcessAfter() {
        return processAfter;
    }

    public void setProcessAfter(Instant processAfter) {
        this.processAfter = processAfter;
    }

}
