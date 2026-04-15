package com.backyard.playground.data.model.chat;

import java.util.UUID;

import com.backyard.playground.data.persist.cassandra.chat.entity.MessageByChannel;

/**
 * Wire representation of a channel message — used as both the REST history
 * response and the {@link ChatEvent} payload for WebSocket push events.
 *
 * <p>Event semantics (created / edited / deleted) are carried by
 * {@link ChatEvent#type()} at the envelope level, not by flags here.
 *
 * <p>{@code createdAt} is intentionally omitted — clients derive it from
 * the UUID v7 {@code messageId} (high 48 bits = Unix ms timestamp).
 */
public class MessageDTO {

    private UUID messageId;
    private UUID channelId;
    private UUID senderId;
    private String body;
    private boolean deleted;
    private boolean edited;
    private boolean hasThread;

    public static MessageDTO from(MessageByChannel entity) {
        MessageDTO dto = new MessageDTO();
        dto.messageId = entity.getMessageId();
        dto.channelId = entity.getChannelId();
        dto.senderId = entity.getSenderId();
        dto.body = entity.getBody();
        dto.deleted = entity.isDeleted();
        dto.edited = entity.isEdited();
        dto.hasThread = entity.isHasThread();
        return dto;
    }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public boolean isHasThread() { return hasThread; }
    public void setHasThread(boolean hasThread) { this.hasThread = hasThread; }
}
