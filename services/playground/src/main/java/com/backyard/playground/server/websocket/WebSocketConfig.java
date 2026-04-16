package com.backyard.playground.server.websocket;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Registers the raw WebSocket endpoint at {@code /ws}.
 *
 * <p>No STOMP, no message broker. The handler owns its own routing map
 * ({@code topic → Set<WebSocketSession>}) and manages Redis subscriptions
 * dynamically — see {@link ChatWebSocketHandler}.
 *
 * <p>Client connection (userId passed as query param — browsers do not
 * support custom headers on native WebSocket):
 * <pre>
 *   const ws = new WebSocket('ws://host/ws?userId=<uuid>');
 *   ws.onmessage = e => console.log(JSON.parse(e.data));
 * </pre>
 */
@Configuration
@EnableWebSocket
@Profile("!home")
public class WebSocketConfig implements WebSocketConfigurer {

    private static final String USER_ID_ATTR = "X-Mock-User-Id";
    private static final String SOCKET_ID_ATTR = "X-Socket-Id";

    private final ChatWebSocketHandler chatHandler;

    public WebSocketConfig(ChatWebSocketHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws")
                .addInterceptors(new MockUserHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /**
     * Reads {@code userId} from the WebSocket upgrade URL query string and
     * stores it in session attributes.
     *
     * <p>Browser WebSocket API does not support custom request headers, so the
     * caller identity is passed as a query parameter instead:
     * {@code ws://host/ws?userId=<uuid>}
     *
     * <p>REST endpoints still use the {@code X-Mock-User-Id} header — only
     * the WebSocket handshake uses the query parameter.
     */
    private final class MockUserHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        if ("userId".equals(kv[0])) attributes.put(USER_ID_ATTR, kv[1]);
                        else if ("socketId".equals(kv[0])) attributes.put(SOCKET_ID_ATTR, kv[1]);
                    }
                }
            }
            return true;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {
        }
    }
}
