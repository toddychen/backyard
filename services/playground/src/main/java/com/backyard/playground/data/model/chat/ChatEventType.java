package com.backyard.playground.data.model.chat;

/** Discriminator for WebSocket push events. */
public enum ChatEventType {
    MESSAGE_CREATED,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    /** Sent to the user's personal inbox when they join a channel. */
    CHANNEL_JOINED,
    /** Sent to the user's personal inbox when they leave a channel. */
    CHANNEL_LEFT
}
