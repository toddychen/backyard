package com.backyard.playground.service;

import com.backyard.playground.data.persist.h2.dory.RecurrenceType;
import com.backyard.playground.data.persist.h2.dory.ReminderEntry;
import com.backyard.playground.data.persist.h2.dory.ReminderRepository;
import com.backyard.playground.data.persist.h2.dory.ReminderStatus;
import com.backyard.playground.data.model.dory.ReminderEntryInputDTO;
import com.backyard.playground.data.model.dory.ReminderEntryDTO;
import com.backyard.playground.exception.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class DoryService {

    private final ReminderRepository repository;

    public DoryService(ReminderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ReminderEntryDTO> findAll(String owner, ReminderStatus status) {
        return repository.findByOwnerAndStatus(owner, status).stream()
                .map(ReminderEntryDTO::from)
                .toList();
    }

    @Transactional
    public ReminderEntryDTO create(String owner, ReminderEntryInputDTO req) {
        var entity = new ReminderEntry();
        entity.setId(UUID.randomUUID());
        entity.setOwner(owner);
        entity.setTitle(req.title());
        entity.setType(req.type());
        entity.setRecurrenceType(req.recurrenceType());
        entity.setNextOccurrence(req.nextOccurrence());
        entity.setSnoozeUntil(req.snoozeUntil());
        entity.setDescription(req.description());
        return ReminderEntryDTO.from(repository.save(entity));
    }

    @Transactional
    public ReminderEntryDTO update(UUID id, ReminderEntryInputDTO req) {
        var entity = findById(id);
        entity.setTitle(req.title());
        entity.setType(req.type());
        entity.setRecurrenceType(req.recurrenceType());
        entity.setNextOccurrence(req.nextOccurrence());
        entity.setSnoozeUntil(req.snoozeUntil());
        entity.setDescription(req.description());
        return ReminderEntryDTO.from(repository.save(entity));
    }

    @Transactional
    public ReminderEntryDTO markDone(UUID id) {
        var entity = findById(id);
        if ("recurring".equals(entity.getType())) {
            entity.setNextOccurrence(
                    advance(entity.getNextOccurrence(), entity.getRecurrenceType()));
            entity.setSnoozeUntil(null);
        } else {
            entity.setStatus(ReminderStatus.DONE);
            entity.setCompletedAt(Instant.now());
        }
        return ReminderEntryDTO.from(repository.save(entity));
    }

    @Transactional
    public ReminderEntryDTO snooze(UUID id, int minutes) {
        var entity = findById(id);
        var base = entity.getSnoozeUntil() != null
                ? entity.getSnoozeUntil()
                : entity.getNextOccurrence();
        entity.setSnoozeUntil(base.plus(minutes, ChronoUnit.MINUTES));
        return ReminderEntryDTO.from(repository.save(entity));
    }

    @Transactional
    public void delete(UUID id) {
        findById(id);
        repository.deleteById(id);
    }

    // -- helpers --

    private ReminderEntry findById(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Reminder not found: " + id));
    }

    private Instant advance(Instant current, RecurrenceType type) {
        return switch (type) {
        case DAILY -> current.plus(1, ChronoUnit.DAYS);
        case WEEKLY -> current.plus(7, ChronoUnit.DAYS);
        case MONTHLY -> current.atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant();
        case YEARLY -> current.atZone(java.time.ZoneOffset.UTC).plusYears(1).toInstant();
        };
    }
}
