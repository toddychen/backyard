package com.backyard.playground.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backyard.playground.data.dory.RecurrenceType;
import com.backyard.playground.data.dory.ReminderEntity;
import com.backyard.playground.data.dory.ReminderRepository;
import com.backyard.playground.data.dory.ReminderRequest;
import com.backyard.playground.data.dory.ReminderResponse;
import com.backyard.playground.data.dory.ReminderStatus;
import com.backyard.playground.exception.NotFoundException;

@Service
public class DoryService {

    private final ReminderRepository repository;

    public DoryService(ReminderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ReminderResponse> findAll(String owner, ReminderStatus status) {
        return repository.findByOwnerAndStatus(owner, status)
                .stream()
                .map(ReminderResponse::from)
                .toList();
    }

    @Transactional
    public ReminderResponse create(String owner, ReminderRequest req) {
        var entity = new ReminderEntity();
        entity.setId(UUID.randomUUID());
        entity.setOwner(owner);
        entity.setTitle(req.title());
        entity.setType(req.type());
        entity.setRecurrenceType(req.recurrenceType());
        entity.setNextOccurrence(req.nextOccurrence());
        entity.setSnoozeUntil(req.snoozeUntil());
        entity.setDescription(req.description());
        return ReminderResponse.from(repository.save(entity));
    }

    @Transactional
    public ReminderResponse update(UUID id, ReminderRequest req) {
        var entity = findById(id);
        entity.setTitle(req.title());
        entity.setType(req.type());
        entity.setRecurrenceType(req.recurrenceType());
        entity.setNextOccurrence(req.nextOccurrence());
        entity.setSnoozeUntil(req.snoozeUntil());
        entity.setDescription(req.description());
        return ReminderResponse.from(repository.save(entity));
    }

    @Transactional
    public ReminderResponse markDone(UUID id) {
        var entity = findById(id);
        if ("recurring".equals(entity.getType())) {
            entity.setNextOccurrence(
                    advance(entity.getNextOccurrence(),
                            entity.getRecurrenceType()));
            entity.setSnoozeUntil(null);
        } else {
            entity.setStatus(ReminderStatus.DONE);
            entity.setCompletedAt(Instant.now());
        }
        return ReminderResponse.from(repository.save(entity));
    }

    @Transactional
    public ReminderResponse snooze(UUID id, int minutes) {
        var entity = findById(id);
        var base = entity.getSnoozeUntil() != null
                ? entity.getSnoozeUntil()
                : entity.getNextOccurrence();
        entity.setSnoozeUntil(base.plus(minutes, ChronoUnit.MINUTES));
        return ReminderResponse.from(repository.save(entity));
    }

    @Transactional
    public void delete(UUID id) {
        findById(id);
        repository.deleteById(id);
    }

    // -- helpers --

    private ReminderEntity findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Reminder not found: " + id));
    }

    private Instant advance(Instant current, RecurrenceType type) {
        return switch (type) {
            case DAILY -> current.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> current.plus(7, ChronoUnit.DAYS);
            case MONTHLY -> current.atZone(java.time.ZoneOffset.UTC)
                    .plusMonths(1).toInstant();
            case YEARLY -> current.atZone(java.time.ZoneOffset.UTC)
                    .plusYears(1).toInstant();
        };
    }
}
