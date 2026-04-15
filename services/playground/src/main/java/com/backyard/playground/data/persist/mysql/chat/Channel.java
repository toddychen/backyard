package com.backyard.playground.data.persist.mysql.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * MySQL entity for the channels table.
 * Stores channel metadata. last_message_at is updated on every message
 * send so the channel list can be sorted by recent activity.
 */
@Entity
@Table(name = "channels")
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)", updatable = false, nullable = false)
    private UUID id;

    @Column(length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * UUID v7 of the most recent message — used to sort the channel list and
     * for unread checks (compare against lastReadMessageId; creation time is
     * derivable from the UUID itself so no separate timestamp column needed).
     */
    @Column(name = "last_message_id", columnDefinition = "BINARY(16)")
    private UUID lastMessageId;

    protected Channel() {
    }

    public Channel(String name, ChannelType type) {
        this.name = name;
        this.type = type;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ChannelType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(UUID lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public enum ChannelType {
        /** Open channel — anyone in the workspace can join. */
        PUBLIC,
        /** Private group channel — invite-only, multiple members. */
        PRIVATE,
        /** Direct message — exactly two participants, no membership check. */
        DM
    }
}
