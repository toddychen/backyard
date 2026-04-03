package com.backyard.playground.data.dory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<ReminderEntity, UUID> {

    List<ReminderEntity> findByOwnerAndStatus(String owner, ReminderStatus status);
}
