package com.backyard.playground.service.driver;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!home")
public class SessionManager {

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    public ClientSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ClientSession::new);
    }

    public ClientSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
