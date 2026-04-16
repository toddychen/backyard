package com.backyard.playground.server.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.backyard.playground.data.model.chat.ChatEvent;
import com.backyard.playground.data.model.chat.ChatEventType;
import com.backyard.playground.data.model.chat.ClientEvent;
import com.backyard.playground.service.chat.ChannelService;
import com.backyard.playground.service.chat.ChatService;
import tools.jackson.databind.ObjectMapper;

/**
 * Raw WebSocket handler for the chat domain.
 *
 * <p>
 * Owns two maps:
 * <ul>
 * <li>{@code sessionsByTopic} — Redis topic → sessions subscribed to it. Used
 * to route incoming Redis messages to the right sockets.</li>
 * <li>{@code topicsBySession} — session ID → topics it subscribed to. Used to
 * clean up on disconnect.</li>
 * </ul>
 *
 * <p>
 * Redis subscriptions are managed dynamically:
 * <ul>
 * <li>First local subscriber for a topic → subscribe this node to Redis.</li>
 * <li>Last local subscriber leaves → unsubscribe from Redis.</li>
 * </ul>
 *
 * <p>
 * On connect, the server auto-subscribes the session to all relevant topics —
 * no client-side SUBSCRIBE messages needed:
 * <ul>
 * <li>{@code chat.user.{userId}} — personal inbox (DMs, thread replies)</li>
 * <li>{@code chat.channel.{id}} — every PUBLIC/PRIVATE channel the user has
 * joined</li>
 * </ul>
 *
 * <p>
 * Client sends {@link ClientEvent} as JSON. Server pushes
 * {@link com.backyard.playground.data.model.chat.ChatEvent} JSON directly to
 * matching sessions — no re-serialisation, no broker middleware.
 */
@Component
@Profile("!home")
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final String USER_ID_ATTR = "X-Mock-User-Id";
    private static final String SOCKET_ID_ATTR = "X-Socket-Id";

    /** Redis topic → local sessions subscribed to it. */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByTopic = new ConcurrentHashMap<>();

    /**
     * Session ID → topics it subscribed to. Avoids scanning all of sessionsByTopic
     * on disconnect — O(topics this session subscribed to) instead of O(all active
     * topics on this node).
     */
    private final ConcurrentHashMap<String, Set<String>> topicsBySession = new ConcurrentHashMap<>();

    private final RedisMessageListenerContainer redisContainer;
    private final ChannelService channelService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * Single listener instance reused across all topic registrations so that
     * removeMessageListener can match on the same reference.
     */
    private final MessageListener redisListener = (message, pattern) -> onRedisMessage(
            new String(message.getBody(), StandardCharsets.UTF_8),
            new String(message.getChannel(), StandardCharsets.UTF_8));

    public ChatWebSocketHandler(
            RedisMessageListenerContainer redisContainer,
            ChannelService channelService,
            ChatService chatService,
            ObjectMapper objectMapper) {
        this.redisContainer = redisContainer;
        this.channelService = channelService;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = getUserId(session);
        if (userId == null) {
            log.warn("WebSocket connected without user ID, closing: {}", session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
            }
            return;
        }
        // Personal inbox — DMs and thread reply notifications.
        addSubscription(session, "chat.user." + userId);
        // All joined PUBLIC/PRIVATE channels (DMs route through the user inbox).
        List<UUID> channelIds = channelService.listJoinedChannelIds(userId);
        channelIds.forEach(id -> addSubscription(session, "chat.channel." + id));
        log.debug("WebSocket connected: userId={} inbox=chat.user.{} channels={}",
                userId, userId, channelIds.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws Exception {
        ClientEvent msg = objectMapper.readValue(message.getPayload(), ClientEvent.class);
        switch (msg.type()) {
        case MARK_READ -> {
            UUID userId = getUserId(session);
            if (userId == null)
                return;
            chatService.markRead(msg.channelId(), userId, msg.lastReadMessageId());
        }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<String> topics = topicsBySession.remove(session.getId());
        if (topics != null) {
            topics.forEach(topic -> {
                try {
                    removeSubscription(session, topic);
                } catch (Exception e) {
                    log.error("Failed to clean up subscription for session {} topic {}: {}",
                            session.getId(), topic, e.getMessage());
                }
            });
        }
        log.debug("WebSocket disconnected: {} status={}", session.getId(), status);
    }

    // ── Redis → WebSocket delivery ─────────────────────────────────────────

    /**
     * Called by the Redis listener when a message arrives on a subscribed topic.
     * Writes the raw JSON body directly to every matching local session.
     */
    public void onRedisMessage(String body, String topic) {
        ChatEvent event = parseEvent(body);
        if (event == null)
            return;

        // ── Control events (arrive on chat.user.{userId}) ─────────────────────
        // topic = "chat.user.{userId}" — sessions here are the user's own
        // inbox sessions. Update their channel subscriptions on this node,
        // then forward the event to the client so the sidebar can refresh.
        if (event.type() == ChatEventType.CHANNEL_JOINED || event.type() == ChatEventType.CHANNEL_LEFT) {
            Set<WebSocketSession> userSessions = sessionsByTopic.get(topic);
            if (userSessions == null || userSessions.isEmpty())
                return;
            UUID channelId = event.channel() != null ? event.channel().getId() : null;
            if (channelId != null) {
                String channelTopic = "chat.channel." + channelId;
                for (WebSocketSession session : userSessions) {
                    if (event.type() == ChatEventType.CHANNEL_JOINED) {
                        addSubscription(session, channelTopic);
                    } else {
                        removeSubscription(session, channelTopic);
                    }
                }
            }
            TextMessage msg = new TextMessage(body);
            userSessions.forEach(s -> deliverToSession(s, msg, topic));
            return;
        }

        // ── Message events (arrive on chat.channel.{id} or chat.user.{id}) ────
        // Skip the originating socket — it already has the data from the REST
        // response. Other sockets of the same user (e.g. a second tab) still
        // receive the push because they have a different socketId.
        Set<WebSocketSession> sessions = sessionsByTopic.get(topic);
        if (sessions == null || sessions.isEmpty())
            return;
        String originSocketId = event.socketId();
        TextMessage msg = new TextMessage(body);
        for (WebSocketSession session : sessions) {
            if (originSocketId != null
                    && originSocketId.equals(session.getAttributes().get(SOCKET_ID_ATTR))) {
                continue;
            }
            deliverToSession(session, msg, topic);
        }
    }

    private ChatEvent parseEvent(String body) {
        try {
            return objectMapper.readValue(body, ChatEvent.class);
        } catch (Exception e) {
            log.warn("Failed to parse ChatEvent: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Delivers a message to a single session, with cleanup on failure. Handles both
     * pre-detected closed sessions (lazy cleanup) and abrupt disconnects discovered
     * during send.
     */
    private void deliverToSession(WebSocketSession session, TextMessage msg, String topic) {
        if (!session.isOpen()) {
            removeSubscription(session, topic);
            return;
        }
        try {
            session.sendMessage(msg);
        } catch (IOException e) {
            log.error("Failed to deliver to session {}: {}", session.getId(), e.getMessage());
            if (!session.isOpen()) {
                removeSubscription(session, topic);
            }
        }
    }

    // ── Subscription lifecycle ─────────────────────────────────────────────

    private void addSubscription(WebSocketSession session, String topic) {
        sessionsByTopic.compute(topic, (key, existing) -> {
            if (existing == null) {
                existing = ConcurrentHashMap.newKeySet();
                redisContainer.addMessageListener(redisListener, new ChannelTopic(key));
                log.debug("Redis subscribed: {}", key);
            }
            existing.add(session);
            topicsBySession.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet())
                    .add(topic);
            return existing;
        });
    }

    private void removeSubscription(WebSocketSession session, String topic) {
        sessionsByTopic.computeIfPresent(topic, (key, sessions) -> {
            sessions.remove(session);
            // Clean up reverse index atomically. No-op if afterConnectionClosed
            // already removed the whole entry; fixes the leak for lazy-cleanup path.
            Set<String> topics = topicsBySession.get(session.getId());
            if (topics != null)
                topics.remove(topic);
            if (sessions.isEmpty()) {
                redisContainer.removeMessageListener(redisListener, new ChannelTopic(key));
                log.debug("Redis unsubscribed: {}", key);
                return null; // removes the key from sessionsByTopic
            }
            return sessions;
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private UUID getUserId(WebSocketSession session) {
        Object raw = session.getAttributes().get(USER_ID_ATTR);
        return raw != null ? UUID.fromString((String) raw) : null;
    }
}
