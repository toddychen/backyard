package com.backyard.playground.data.model.chat;

/**
 * Envelope for all WebSocket push events.
 *
 * <p>Client dispatches on {@code type}:
 * <pre>
 *   ws.onmessage = (e) => {
 *       const { type, payload } = JSON.parse(e.data);
 *       switch (type) {
 *           case 'MESSAGE_CREATED': handleNew(payload); break;
 *           case 'MESSAGE_EDITED':  handleEdit(payload); break;
 *           case 'MESSAGE_DELETED': handleDelete(payload); break;
 *       }
 *   };
 * </pre>
 */
/**
 * {@code message} is set for MESSAGE_* events.
 * {@code channel} is set for CHANNEL_JOINED / CHANNEL_LEFT control events.
 */
public record ChatEvent(ChatEventType type, MessageDTO message, ChannelDTO channel) {}
