package com.backyard.playground.service.driver;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.collections4.CollectionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.driver.DriverPositionDTO;
import com.backyard.playground.data.model.driver.MembershipUpdateDTO;
import com.backyard.playground.data.model.driver.ViewportDTO;

@Service
@Profile("!home")
public class MembershipPushService {

    private static final Logger log = LoggerFactory.getLogger(MembershipPushService.class);

    private final DriverRedisRepository repository;
    private final S2Service s2Service;
    private final SimpMessagingTemplate messaging;

    public MembershipPushService(
            DriverRedisRepository repository,
            S2Service s2Service,
            SimpMessagingTemplate messaging) {
        this.repository = repository;
        this.s2Service = s2Service;
        this.messaging = messaging;
    }

    public void push(ClientSession session) {
        try {
            ViewportDTO viewport = session.getViewport();
            if (viewport == null || viewport.getZoom() < 12)
                return;

            List<long[]> ranges = s2Service.coverViewport(viewport);
            Set<UUID> current = repository.queryViewport(ranges);
            Set<UUID> known = session.getKnownDriverIds();
            List<UUID> entered = List.copyOf(CollectionUtils.subtract(current, known));
            List<UUID> left = List.copyOf(CollectionUtils.subtract(known, current));

            log.info("Membership push for {}: current={} entered={} left={}",
                    session.getSessionId(), current.size(), entered.size(), left.size());

            if (entered.isEmpty() && left.isEmpty())
                return;

            session.setKnownDriverIds(current);

            List<DriverPositionDTO> enteredPositions = repository.getPositions(entered);
            messaging.convertAndSendToUser(session.getSessionId(), "/queue/driver/membership",
                    new MembershipUpdateDTO(enteredPositions, left));
        } catch (Exception e) {
            log.error("Membership push failed for {}: {}", session.getSessionId(), e.getMessage(), e);
        }
    }
}
