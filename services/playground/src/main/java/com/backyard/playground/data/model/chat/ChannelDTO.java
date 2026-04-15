package com.backyard.playground.data.model.chat;

import java.time.Instant;
import java.util.UUID;

import com.backyard.playground.data.persist.mysql.chat.Channel;
import com.backyard.playground.data.persist.mysql.chat.Channel.ChannelType;

public class ChannelDTO {

    private UUID id;
    private String name;
    private ChannelType type;
    private Instant createdAt;
    /** UUID v7 of the most recent message — creation time derivable from it. */
    private UUID lastMessageId;
    /** True if the channel has messages the caller has not read yet. */
    private boolean unread;
    /** Other participant's user ID — populated for DM channels only. */
    private UUID otherUserId;
    /** True if the caller is already a member — populated by browsePublicChannels. */
    private boolean joined;

    /** Minimal DTO carrying only an id — used for CHANNEL_LEFT control events. */
    public static ChannelDTO withId(UUID id) {
        ChannelDTO dto = new ChannelDTO();
        dto.id = id;
        return dto;
    }

    public static ChannelDTO from(Channel channel) {
        return from(channel, null, null, true);
    }

    /**
     * Build a DTO with unread state and, for DM channels, the other participant.
     * {@code lastReadMessageId} null means the user has never opened the channel.
     * {@code otherUserId} null for non-DM channels.
     */
    public static ChannelDTO from(Channel channel, UUID lastReadMessageId, UUID otherUserId) {
        return from(channel, lastReadMessageId, otherUserId, true);
    }

    /**
     * Full factory — includes the {@code joined} flag used by the browse
     * endpoint.
     */
    public static ChannelDTO from(
            Channel channel, UUID lastReadMessageId, UUID otherUserId, boolean joined) {
        ChannelDTO dto = new ChannelDTO();
        dto.id = channel.getId();
        dto.name = channel.getName();
        dto.type = channel.getType();
        dto.createdAt = channel.getCreatedAt();
        dto.lastMessageId = channel.getLastMessageId();
        UUID lastMsg = channel.getLastMessageId();
        dto.unread = lastMsg != null
                && (lastReadMessageId == null || lastReadMessageId.compareTo(lastMsg) < 0);
        dto.otherUserId = otherUserId;
        dto.joined = joined;
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
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

    public boolean isUnread() {
        return unread;
    }

    public UUID getOtherUserId() {
        return otherUserId;
    }

    public boolean isJoined() {
        return joined;
    }
}
