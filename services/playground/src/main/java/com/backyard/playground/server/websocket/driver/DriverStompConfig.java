package com.backyard.playground.server.websocket.driver;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@Profile("!home")
public class DriverStompConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Client connects to: new SockJS('/driver/ws')
        registry.addEndpoint("/driver/ws").withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Assign session ID as principal so convertAndSendToUser() can route to
        // anonymous sessions
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String sessionId = accessor.getSessionId();
                    accessor.setUser(() -> sessionId);
                }
                return message;
            }
        });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker-managed destinations; client subscribes to e.g.
        // /user/queue/driver/positions
        registry.enableSimpleBroker("/queue", "/topic");
        // Client sends to /app/driver/viewport; Spring strips /app and routes to
        // @MessageMapping
        registry.setApplicationDestinationPrefixes("/app");
        // convertAndSendToUser() routes to /user/{sessionId}/queue/…; client subscribes
        // to /user/queue/…
        registry.setUserDestinationPrefix("/user");
    }
}
