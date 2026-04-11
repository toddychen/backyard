package com.backyard.notification.common.kafka.message;

import java.util.Map;
import java.util.UUID;

/**
 * Published to notification.fanout — one per Cassandra token range segment.
 * N=100 messages per event, keyed by rangeIndex % 16 at publish time (not
 * stored in the message).
 *
 * Consumer queries: SELECT user_id FROM subscriptions WHERE topic_id = :topicId
 * AND token(user_id) >= :rangeStart AND token(user_id) < :rangeEnd
 *
 * data map: arbitrary key/value metadata for custom notification payload.
 */
public class FanoutMessage {

    private String topicId;
    /**
     * Unique ID for this event — carried through the pipeline for tracing. Future:
     * store title/body in a separate DB keyed by eventId (late binding), so the
     * sender fetches content at send time. Benefits: smaller messages, support for
     * localization per device locale, and content corrections after the event
     * fires.
     */
    private UUID eventId;
    /** Inline for simplicity — see eventId comment for late-binding alternative. */
    private String title;
    /** Inline for simplicity — see eventId comment for late-binding alternative. */
    private String body;
    private Map<String, String> data;
    /** Inclusive start of the Cassandra token range (Murmur3 hash). */
    private long rangeStart;
    /** Exclusive end of the Cassandra token range (Murmur3 hash). */
    private long rangeEnd;

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

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

    public long getRangeStart() {
        return rangeStart;
    }

    public void setRangeStart(long rangeStart) {
        this.rangeStart = rangeStart;
    }

    public long getRangeEnd() {
        return rangeEnd;
    }

    public void setRangeEnd(long rangeEnd) {
        this.rangeEnd = rangeEnd;
    }
}
