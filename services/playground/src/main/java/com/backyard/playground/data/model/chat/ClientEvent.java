package com.backyard.playground.data.model.chat;

import java.util.UUID;

/**
 * Incoming WebSocket message from the client.
 *
 * <pre>
 *   { "type": "MARK_READ", "channelId": "{id}", "lastReadMessageId": "{id}" }
 * </pre>
 */
public record ClientEvent(Type type, UUID channelId, UUID lastReadMessageId) {

    public enum Type {
        MARK_READ
    }
}
