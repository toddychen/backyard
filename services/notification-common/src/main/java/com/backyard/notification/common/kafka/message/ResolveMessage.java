package com.backyard.notification.common.kafka.message;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published to notification.resolve — batches of up to 1000 user_ids.
 *
 * The consumer fires 1000 concurrent async reads to push_destinations_by_user,
 * filters deliverable destinations, and splits by platform into SendMessages.
 */
public class ResolveMessage {

    private String topicId;
    /**
     * Origin event ID — for tracing. Future: use for late-binding content lookup.
     */
    private UUID eventId;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String title;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String body;
    private Map<String, String> data;
    private List<UUID> userIds;

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

    public List<UUID> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<UUID> userIds) {
        this.userIds = userIds;
    }
}
