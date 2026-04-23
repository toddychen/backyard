package com.backyard.playground.server.websocket.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Defines the Redis listener container for chat pub/sub.
 *
 * Kept separate from {@link WebSocketConfig} to avoid a circular
 * dependency: WebSocketConfig needs ChatWebSocketHandler, and
 * ChatWebSocketHandler needs RedisMessageListenerContainer.
 * Putting the container bean here breaks that cycle.
 *
 * No static topic subscriptions are registered here — ChatWebSocketHandler
 * adds and removes topics dynamically as local WebSocket clients
 * subscribe and unsubscribe.
 */
@Configuration
@Profile("!home")
public class ChatRedisConfig {

    @Bean
    public RedisMessageListenerContainer chatRedisListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
