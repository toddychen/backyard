package com.backyard.playground.data.dory;

import java.time.Instant;
import java.util.UUID;

public record ReminderResponse(
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

    public static ReminderResponse from(ReminderEntity e) {
        return new ReminderResponse(
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
