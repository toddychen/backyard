package com.backyard.playground.server.websocket;

import java.util.UUID;

import com.backyard.playground.data.model.chat.ChannelDTO;
import com.backyard.playground.data.model.chat.ChatEventType;
import com.backyard.playground.data.model.chat.MessageDTO;
import com.backyard.playground.data.model.chat.ReplyDTO;

/**
 * Publishes chat events to Redis so every node can fan out
 * to its locally connected WebSocket clients.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link RedisPubServiceImpl} — active on all profiles
 *       except {@code home}, uses Redis pub/sub.</li>
 *   <li>{@link RedisPubServiceNoOp} — active on the {@code home}
 *       profile where Redis is not available.</li>
 * </ul>
 */
public interface RedisPubService {

    /**
     * Publish a channel event (new/edit/delete) to all subscribers of that channel.
     * {@code socketId} identifies the originating socket and is excluded from delivery.
     */
    void publishChannelEvent(UUID channelId, ChatEventType type, MessageDTO dto, String socketId);

    /**
     * Publish a DM event to the recipient's personal inbox topic.
     * The payload includes channelId so the client can render the sidebar
     * entry and pull history on demand.
     * {@code socketId} identifies the originating socket and is excluded from delivery.
     */
    void publishDmEvent(UUID recipientId, ChatEventType type, MessageDTO dto, String socketId);

    /**
     * Publish a reply event (created/edited/deleted) to all subscribers of that channel.
     * {@code socketId} identifies the originating socket and is excluded from delivery.
     */
    void publishChannelReplyEvent(UUID channelId, ChatEventType type, ReplyDTO dto, String socketId);

    /**
     * Publish a reply event to a DM recipient's personal inbox topic.
     * {@code socketId} identifies the originating socket and is excluded from delivery.
     */
    void publishDmReplyEvent(UUID recipientId, ChatEventType type, ReplyDTO dto, String socketId);

    /**
     * Publish a control event (CHANNEL_JOINED / CHANNEL_LEFT) to the user's
     * personal inbox topic so the node holding their WebSocket can update
     * its Redis subscriptions without the REST node knowing which node
     * that is.
     */
    void publishUserControlEvent(UUID userId, ChatEventType type, ChannelDTO channel);
}
