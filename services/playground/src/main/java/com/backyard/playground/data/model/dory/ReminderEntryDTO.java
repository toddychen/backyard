package com.backyard.playground.data.model.dory;

import com.backyard.playground.data.persist.h2.dory.RecurrenceType;
import com.backyard.playground.data.persist.h2.dory.ReminderEntry;
import com.backyard.playground.data.persist.h2.dory.ReminderStatus;

import java.time.Instant;
import java.util.UUID;

public record ReminderEntryDTO(
        UUID id,
        String owner,
        String title,
        String type,
        RecurrenceType recurrenceType,
        Instant nextOccurrence,
        Instant snoozeUntil,
        String description,
        ReminderStatus status,
        Instant completedAt) {

    public static ReminderEntryDTO from(ReminderEntry e) {
        return new ReminderEntryDTO(
                e.getId(),
                e.getOwner(),
                e.getTitle(),
                e.getType(),
                e.getRecurrenceType(),
                e.getNextOccurrence(),
                e.getSnoozeUntil(),
                e.getDescription(),
                e.getStatus(),
                e.getCompletedAt());
    }
}
