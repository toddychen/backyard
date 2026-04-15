package com.backyard.playground.server.websocket;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.chat.ChannelDTO;
import com.backyard.playground.data.model.chat.ChatEventType;
import com.backyard.playground.data.model.chat.MessageDTO;

/**
 * No-op relay used on the {@code home} profile where Redis is not
 * available. Events are logged at DEBUG level and dropped.
 */
@Service
@Profile("home")
public class RedisPubServiceNoOp implements RedisPubService {

    private static final Logger log = LoggerFactory.getLogger(RedisPubServiceNoOp.class);

    @Override
    public void publishChannelEvent(UUID channelId, ChatEventType type, MessageDTO dto) {
        log.debug("RedisPubServiceNoOp: channel event dropped (channelId={} type={})", channelId, type);
    }

    @Override
    public void publishDmEvent(UUID recipientId, ChatEventType type, MessageDTO dto) {
        log.debug("RedisPubServiceNoOp: DM event dropped (recipientId={} type={})", recipientId, type);
    }

    @Override
    public void publishUserControlEvent(UUID userId, ChatEventType type, ChannelDTO channel) {
        log.debug("RedisPubServiceNoOp: control event dropped (userId={} type={})", userId, type);
    }
}
