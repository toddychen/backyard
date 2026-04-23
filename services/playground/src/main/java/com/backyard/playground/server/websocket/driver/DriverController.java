package com.backyard.playground.server.websocket.driver;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.backyard.playground.data.model.driver.ViewportDTO;
import com.backyard.playground.service.driver.ClientSession;
import com.backyard.playground.service.driver.MembershipPushService;
import com.backyard.playground.service.driver.PositionPushService;
import com.backyard.playground.service.driver.SessionManager;

@Controller
@Profile("!home")
public class DriverController {

    private static final Logger log = LoggerFactory.getLogger(DriverController.class);

    private final SessionManager sessions;
    private final PositionPushService positionPush;
    private final MembershipPushService membershipPush;
    private final ScheduledExecutorService scheduler;

    public DriverController(
            SessionManager sessions,
            PositionPushService positionPush,
            MembershipPushService membershipPush,
            @Qualifier("driverSimulatorScheduler") ScheduledExecutorService scheduler) {
        this.sessions = sessions;
        this.positionPush = positionPush;
        this.membershipPush = membershipPush;
        this.scheduler = scheduler;
    }

    @MessageMapping("/driver/viewport")
    public void handleViewport(ViewportDTO msg, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        log.info("Viewport received from {}: zoom={} bounds=[{},{},{},{}]",
                sessionId, msg.getZoom(), msg.getMinLat(), msg.getMaxLat(), msg.getMinLng(), msg.getMaxLng());
        ClientSession session = sessions.getOrCreate(sessionId);
        session.cancelTasks();
        session.setViewport(msg);

        if (msg.getZoom() >= 12) {
            session.setPositionFuture(scheduler.scheduleAtFixedRate(
                    () -> positionPush.push(session), 0, 1, TimeUnit.SECONDS));
            session.setMembershipFuture(scheduler.scheduleAtFixedRate(
                    () -> membershipPush.push(session), 0, 10, TimeUnit.SECONDS));
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        ClientSession session = sessions.get(sessionId);
        if (session != null) {
            session.cancelTasks();
            sessions.remove(sessionId);
        }
    }
}
