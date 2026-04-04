package com.backyard.playground.data.persist.h2.dory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<ReminderEntry, UUID> {

    List<ReminderEntry> findByOwnerAndStatus(String owner, ReminderStatus status);
}
