package com.backyard.playground.data.model;

import java.util.UUID;

import com.backyard.playground.data.persist.mysql.auth.User;

/** Public user profile — safe to send to clients (no email, no password hash). */
public class UserDTO {

    private UUID id;
    private String name;

    public static UserDTO from(User user) {
        UserDTO dto = new UserDTO();
        dto.id = user.getId();
        dto.name = user.getName();
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
