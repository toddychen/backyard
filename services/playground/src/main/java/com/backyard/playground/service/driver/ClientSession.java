package com.backyard.playground.service.driver;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import com.backyard.playground.data.model.driver.ViewportDTO;

public class ClientSession {

    private final String sessionId;
    private volatile ViewportDTO viewport;
    private final Set<UUID> knownDriverIds = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> positionFuture;
    private volatile ScheduledFuture<?> membershipFuture;

    public ClientSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public void cancelTasks() {
        if (positionFuture != null)
            positionFuture.cancel(false);
        if (membershipFuture != null)
            membershipFuture.cancel(false);
        knownDriverIds.clear();
    }

    public String getSessionId() {
        return sessionId;
    }

    public ViewportDTO getViewport() {
        return viewport;
    }

    public void setViewport(ViewportDTO viewport) {
        this.viewport = viewport;
    }

    public Set<UUID> getKnownDriverIds() {
        return knownDriverIds;
    }

    public void setKnownDriverIds(Set<UUID> ids) {
        knownDriverIds.clear();
        knownDriverIds.addAll(ids);
    }

    public void setPositionFuture(ScheduledFuture<?> f) {
        this.positionFuture = f;
    }

    public void setMembershipFuture(ScheduledFuture<?> f) {
        this.membershipFuture = f;
    }
}
