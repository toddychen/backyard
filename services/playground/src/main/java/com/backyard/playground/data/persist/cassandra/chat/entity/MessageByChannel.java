package com.backyard.playground.data.persist.cassandra.chat.entity;

import java.util.UUID;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Cassandra entity for messages_by_channel in the chat keyspace.
 *
 * Partition  : channel_id
 * Clustering : message_id DESC (UUID v7 — high 48 bits = ms timestamp,
 *              so newest-first order without a separate created_at column)
 *
 * Schema source of truth: infra/cassandra/schema.cql
 */
@Table(keyspace = "chat", value = "messages_by_channel")
public class MessageByChannel {

    @PrimaryKeyColumn(name = "channel_id", type = PrimaryKeyType.PARTITIONED)
    private UUID channelId;

    /**
     * UUID v7 — encodes creation time in the high 48 bits.
     * Extract ms timestamp: {@code messageId.getMostSignificantBits() >>> 16}
     */
    @PrimaryKeyColumn(
            name = "message_id",
            type = PrimaryKeyType.CLUSTERED,
            ordering = Ordering.DESCENDING)
    private UUID messageId;

    @Column("sender_id")
    private UUID senderId;

    @Column("body")
    private String body;

    /** Soft delete — never hard-delete to avoid tombstone accumulation. */
    @Column("deleted")
    private boolean deleted;

    @Column("edited")
    private boolean edited;

    /**
     * One-way flag: set to true on first reply, never unset.
     * Lets the channel feed render a thread expand button without
     * querying replies_by_message for every visible message.
     */
    @Column("has_thread")
    private boolean hasThread;

    public MessageByChannel() {
    }

    public UUID getChannelId() {
        return channelId;
    }

    public void setChannelId(UUID channelId) {
        this.channelId = channelId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
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

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public boolean isHasThread() {
        return hasThread;
    }

    public void setHasThread(boolean hasThread) {
        this.hasThread = hasThread;
    }
}
