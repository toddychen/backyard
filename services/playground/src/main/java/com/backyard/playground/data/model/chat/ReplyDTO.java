package com.backyard.playground.data.model.chat;

import java.util.UUID;

import com.backyard.playground.data.persist.cassandra.chat.entity.ReplyByMessage;

/**
 * Wire representation of a thread reply.
 *
 * <p>
 * Event semantics (created / edited / deleted) are carried by the
 * {@link ChatEvent} envelope, not by flags here.
 *
 * <p>
 * {@code createdAt} is intentionally omitted — clients derive it from the UUID
 * v7 {@code messageId} (high 48 bits = Unix ms timestamp).
 */
public class ReplyDTO {

    private UUID messageId;
    private UUID parentId;
    /**
     * Channel that owns the parent message. Included so WebSocket push recipients
     * know which channel/thread to navigate to.
     */
    private UUID channelId;
    private UUID senderId;
    private String body;

    public static ReplyDTO from(ReplyByMessage entity) {
        ReplyDTO dto = new ReplyDTO();
        dto.messageId = entity.getMessageId();
        dto.parentId = entity.getParentId();
        dto.channelId = entity.getChannelId();
        dto.senderId = entity.getSenderId();
        dto.body = entity.getBody();
        return dto;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public UUID getChannelId() {
        return channelId;
    }

    public void setChannelId(UUID channelId) {
        this.channelId = channelId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
