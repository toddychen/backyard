package com.backyard.playground.data.model.dory;

import com.backyard.playground.data.persist.h2.dory.RecurrenceType;

import java.time.Instant;

public record ReminderEntryInputDTO(
        String title,
        String type,
        RecurrenceType recurrenceType,
        Instant nextOccurrence,
        Instant snoozeUntil,
        String description) {
}
