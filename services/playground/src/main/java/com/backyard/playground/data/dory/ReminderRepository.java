package com.backyard.playground.data.dory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderRepository extends JpaRepository<ReminderEntity, UUID> {

    List<ReminderEntity> findByOwnerAndStatus(String owner, ReminderStatus status);
}
