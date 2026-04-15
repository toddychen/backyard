package com.backyard.playground.data.persist.mysql.chat;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * MySQL entity for the channel_members join table.
 *
 * Composite PK (channel_id, user_id). An INDEX on user_id supports both query
 * directions without dual-write: WHERE channel_id = ? — member list (PK prefix)
 * WHERE user_id = ? — "channels I belong to" (secondary index)
 */
@Entity
@Table(name = "channel_members", indexes = @Index(name = "idx_channel_members_user_id", columnList = "user_id"))
public class ChannelMember {

    @EmbeddedId
    private ChannelMemberId id;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    /**
     * UUID v7 of the last message the user read in this channel. Null until the
     * user opens the channel for the first time. Updated on channel open / explicit
     * mark-read.
     */
    @Column(name = "last_read_message_id", columnDefinition = "BINARY(16)")
    private UUID lastReadMessageId;

    protected ChannelMember() {
    }

    public ChannelMember(UUID channelId, UUID userId) {
        this.id = new ChannelMemberId(channelId, userId);
    }

    public ChannelMemberId getId() {
        return id;
    }

    public UUID getChannelId() {
        return id.getChannelId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public UUID getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(UUID lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }

    @Embeddable
    public static class ChannelMemberId implements Serializable {

        @Column(name = "channel_id", columnDefinition = "BINARY(16)", nullable = false)
        private UUID channelId;

        @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
        private UUID userId;

        protected ChannelMemberId() {
        }

        public ChannelMemberId(UUID channelId, UUID userId) {
            this.channelId = channelId;
            this.userId = userId;
        }

        public UUID getChannelId() {
            return channelId;
        }

        public UUID getUserId() {
            return userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ChannelMemberId other))
                return false;
            return channelId.equals(other.channelId) && userId.equals(other.userId);
        }

        @Override
        public int hashCode() {
            return 31 * channelId.hashCode() + userId.hashCode();
        }
    }
}
