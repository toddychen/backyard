package com.backyard.playground.server.websocket;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.chat.ChannelDTO;
import com.backyard.playground.data.model.chat.ChatEvent;
import com.backyard.playground.data.model.chat.ChatEventType;
import com.backyard.playground.data.model.chat.MessageDTO;
import com.backyard.playground.data.model.chat.ReplyDTO;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes chat events to Redis so every node can fan out to its locally
 * connected WebSocket clients via {@link ChatWebSocketHandler}.
 *
 * <p>Each event is wrapped in a {@link ChatEvent} envelope before
 * serialization so clients can dispatch on {@code type}.
 *
 * <p>This class only publishes — receiving and delivery to WebSocket
 * sessions is handled entirely by {@link ChatWebSocketHandler#onRedisMessage}.
 */
@Service
@Profile("!home")
public class RedisPubServiceImpl implements RedisPubService {

    private static final Logger log = LoggerFactory.getLogger(RedisPubServiceImpl.class);

    private static final String CHANNEL_PREFIX = "chat.channel.";
    private static final String USER_PREFIX = "chat.user.";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPubServiceImpl(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishChannelEvent(UUID channelId, ChatEventType type, MessageDTO dto, String socketId) {
        publishJson(CHANNEL_PREFIX + channelId, new ChatEvent(type, dto, null, null, socketId));
    }

    @Override
    public void publishDmEvent(UUID recipientId, ChatEventType type, MessageDTO dto, String socketId) {
        publishJson(USER_PREFIX + recipientId, new ChatEvent(type, dto, null, null, socketId));
    }

    @Override
    public void publishChannelReplyEvent(UUID channelId, ChatEventType type, ReplyDTO dto, String socketId) {
        publishJson(CHANNEL_PREFIX + channelId, new ChatEvent(type, null, dto, null, socketId));
    }

    @Override
    public void publishDmReplyEvent(UUID recipientId, ChatEventType type, ReplyDTO dto, String socketId) {
        publishJson(USER_PREFIX + recipientId, new ChatEvent(type, null, dto, null, socketId));
    }

    @Override
    public void publishUserControlEvent(UUID userId, ChatEventType type, ChannelDTO channel) {
        publishJson(USER_PREFIX + userId, new ChatEvent(type, null, null, channel, null));
    }

    private void publishJson(String topic, ChatEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(topic, json);
        } catch (JacksonException e) {
            log.error("Failed to serialize event for topic {}: {}", topic, e.getMessage());
        }
    }
}
