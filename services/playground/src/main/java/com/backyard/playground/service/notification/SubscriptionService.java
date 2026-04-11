package com.backyard.playground.service.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.backyard.notification.common.cassandra.entity.Subscription;
import com.backyard.notification.common.cassandra.repository.SubscriptionRepository;
import com.backyard.playground.data.model.notification.SubscriptionDTO;
import com.backyard.playground.data.model.notification.SubscriptionInputDTO;
import com.backyard.playground.exception.NotFoundException;

@Service
public class SubscriptionService {

    private final SubscriptionRepository repository;

    public SubscriptionService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    public List<SubscriptionDTO> list(UUID userId) {
        return repository.findByUserId(userId).stream()
                .map(SubscriptionDTO::from)
                .toList();
    }

    /** Subscribe a user to a topic. Idempotent — upserts if already subscribed. */
    public SubscriptionDTO subscribe(UUID userId, SubscriptionInputDTO req) {
        var sub = new Subscription();
        sub.setUserId(userId);
        sub.setTopicId(req.topicId());
        sub.setSubscribedAt(Instant.now());
        return SubscriptionDTO.from(repository.save(sub));
    }

    public void unsubscribe(UUID userId, String topicId) {
        var sub = repository.findByUserIdAndTopicId(userId, topicId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + topicId));
        repository.delete(sub);
    }
}
