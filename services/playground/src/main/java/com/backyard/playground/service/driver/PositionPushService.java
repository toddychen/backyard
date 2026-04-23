package com.backyard.playground.service.driver;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.driver.DriverPositionDTO;
import com.backyard.playground.data.model.driver.PositionUpdateDTO;

@Service
@Profile("!home")
public class PositionPushService {

    private static final Logger log = LoggerFactory.getLogger(PositionPushService.class);

    private final DriverRedisRepository repository;
    private final SimpMessagingTemplate messaging;

    public PositionPushService(DriverRedisRepository repository, SimpMessagingTemplate messaging) {
        this.repository = repository;
        this.messaging = messaging;
    }

    public void push(ClientSession session) {
        try {
            Set<UUID> ids = session.getKnownDriverIds();
            if (ids.isEmpty())
                return;
            List<DriverPositionDTO> positions = repository.getPositions(ids);
            log.info("Position push for {}: known={} fetched={}", session.getSessionId(), ids.size(), positions.size());
            messaging.convertAndSendToUser(session.getSessionId(), "/queue/driver/positions",
                    new PositionUpdateDTO(positions));
        } catch (Exception e) {
            log.debug("Position push failed for {}: {}", session.getSessionId(), e.getMessage());
        }
    }
}
