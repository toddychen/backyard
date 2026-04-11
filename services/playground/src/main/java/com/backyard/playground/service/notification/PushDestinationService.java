package com.backyard.playground.service.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.backyard.notification.common.cassandra.entity.PushDestination;
import com.backyard.notification.common.cassandra.repository.PushDestinationRepository;
import com.backyard.playground.data.model.notification.PushDestinationDTO;
import com.backyard.playground.data.model.notification.PushDestinationInputDTO;
import com.backyard.playground.exception.NotFoundException;

@Service
public class PushDestinationService {

    private static final Logger log = LoggerFactory.getLogger(PushDestinationService.class);

    private final PushDestinationRepository repository;

    public PushDestinationService(PushDestinationRepository repository) {
        this.repository = repository;
    }

    public List<PushDestinationDTO> list(UUID userId) {
        return repository.findByUserId(userId).stream()
                .map(PushDestinationDTO::from)
                .toList();
    }

    /**
     * Register or update a push destination.
     *
     * <p>
     * If {@code oldPushToken} is present, the old row is deleted and a new one is
     * inserted (token rotation) — push_token is part of the Cassandra primary key
     * and cannot be updated in place. If the old token row is not found, a warning
     * is logged and the new row is inserted anyway.
     *
     * <p>
     * Without {@code oldPushToken}, the row for {@code pushToken} is upserted. Safe
     * to call repeatedly with updated fields (locale, notificationsEnabled, etc.).
     */
    public PushDestinationDTO register(UUID userId, PushDestinationInputDTO req) {
        boolean isRotation = req.oldPushToken() != null && !req.oldPushToken().isBlank();

        Instant registeredAt;
        if (isRotation) {
            var existing = repository.findByUserIdAndPushToken(userId, req.oldPushToken());
            if (existing.isPresent()) {
                repository.delete(existing.get());
                registeredAt = existing.get().getRegisteredAt();
            } else {
                log.warn("Token rotation: old push token not found for user {}, continuing", userId);
                registeredAt = Instant.now();
            }
        } else {
            // Preserve registeredAt if token already exists, otherwise set now
            registeredAt = repository.findByUserIdAndPushToken(userId, req.pushToken())
                    .map(PushDestination::getRegisteredAt)
                    .orElse(Instant.now());
        }

        var dest = new PushDestination();
        dest.setUserId(userId);
        dest.setPushToken(req.pushToken());
        dest.setPlatform(req.platform());
        dest.setBundleId(req.bundleId());
        dest.setApnsEnv(req.apnsEnv());
        dest.setLocale(req.locale());
        dest.setNotificationsEnabled(req.notificationsEnabled());
        dest.setTokenValid(true);
        dest.setRegisteredAt(registeredAt);
        dest.setUpdatedAt(Instant.now());
        return PushDestinationDTO.from(repository.save(dest));
    }

    public void delete(UUID userId, String pushToken) {
        var existing = repository.findByUserIdAndPushToken(userId, pushToken)
                .orElseThrow(() -> new NotFoundException("Push destination not found: " + pushToken));
        repository.delete(existing);
    }
}
