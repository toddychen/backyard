package com.backyard.notification.common.kafka.message;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published to notification.send — per-platform batches of push destinations.
 *
 * Batch size limits: APNS: <= 200 tokens (one HTTP/2 call per token,
 * parallelised). FCM: <= 500 tokens (FCM batch API limit).
 */
public class SendMessage {

    /**
     * Origin event ID — for tracing. Future: use for late-binding content lookup.
     */
    private UUID eventId;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String title;
    /** Inline for simplicity — see eventId comment in FanoutMessage. */
    private String body;
    private Map<String, String> data;
    private List<PushDestinationInfo> destinations;

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

    public List<PushDestinationInfo> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<PushDestinationInfo> destinations) {
        this.destinations = destinations;
    }
}
