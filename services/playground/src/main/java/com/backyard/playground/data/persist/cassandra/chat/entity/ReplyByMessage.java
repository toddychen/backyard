package com.backyard.playground.data.persist.cassandra.chat.entity;

import java.util.UUID;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Cassandra entity for replies_by_message in the chat keyspace.
 *
 * Partition  : parent_id — all replies for one thread co-located.
 * Clustering : message_id ASC (UUID v7 — oldest-first, natural reading order)
 *
 * Timestamp is derivable from message_id (high 48 bits = ms epoch).
 * Schema source of truth: infra/cassandra/schema.cql
 */
@Table(keyspace = "chat", value = "replies_by_message")
public class ReplyByMessage {

    @PrimaryKeyColumn(name = "parent_id", type = PrimaryKeyType.PARTITIONED)
    private UUID parentId;

    /**
     * UUID v7 — encodes creation time in the high 48 bits.
     * Ordered ASC so thread reads oldest-first.
     */
    @PrimaryKeyColumn(
            name = "message_id",
            type = PrimaryKeyType.CLUSTERED,
            ordering = Ordering.ASCENDING)
    private UUID messageId;

    /** Denormalised from the parent message — avoids a cross-table lookup on edit/delete. */
    @Column("channel_id")
    private UUID channelId;

    @Column("sender_id")
    private UUID senderId;

    @Column("body")
    private String body;

    @Column("deleted")
    private boolean deleted;

    public ReplyByMessage() {
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
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

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
