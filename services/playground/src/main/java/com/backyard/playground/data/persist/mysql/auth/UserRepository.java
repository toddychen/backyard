package com.backyard.playground.data.persist.mysql.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    @Modifying
    @Query("UPDATE User u SET u.passwordHash = :hash WHERE u.id = :userId")
    void updatePasswordHash(UUID userId, String hash);
}
