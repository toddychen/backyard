package com.backyard.playground.data.dory;

import java.time.Instant;

public record ReminderRequest(
        String title,
        String type,
        RecurrenceType recurrenceType,
        Instant nextOccurrence,
        Instant snoozeUntil,
        String description) {
}
