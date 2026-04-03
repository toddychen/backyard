package com.backyard.playground.data.dory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reminder", indexes = @Index(columnList = "owner, status"))
public class ReminderEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 20)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RecurrenceType recurrenceType;

    @Column(nullable = false)
    private Instant nextOccurrence;

    private Instant snoozeUntil;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderStatus status = ReminderStatus.LIVE;

    private Instant completedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public RecurrenceType getRecurrenceType() {
        return recurrenceType;
    }

    public void setRecurrenceType(RecurrenceType recurrenceType) {
        this.recurrenceType = recurrenceType;
    }

    public Instant getNextOccurrence() {
        return nextOccurrence;
    }

    public void setNextOccurrence(Instant nextOccurrence) {
        this.nextOccurrence = nextOccurrence;
    }

    public Instant getSnoozeUntil() {
        return snoozeUntil;
    }

    public void setSnoozeUntil(Instant snoozeUntil) {
        this.snoozeUntil = snoozeUntil;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public void setStatus(ReminderStatus status) {
        this.status = status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
