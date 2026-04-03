package com.backyard.playground.controller;

import com.backyard.playground.data.auth.User;
import com.backyard.playground.data.auth.UserRepository;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.service.AuthService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/{version}/user")
public class UserController {

    private final UserRepository userRepository;
    private final AuthService authService;

    public UserController(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
    }

    public record UserResponse(UUID id, String email) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** GET /api/v1/user/me */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new NotFoundException("user not found"));
        return ResponseEntity.ok(new UserResponse(user.getId(), user.getEmail()));
    }

    /** POST /api/v1/user/password */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest req,
            @RequestHeader("Authorization") String authHeader,
            @AuthenticationPrincipal UUID userId) {
        String rawAccessToken = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : "";
        authService.changePassword(
                userId, req.currentPassword(), req.newPassword(), rawAccessToken);
        return ResponseEntity.noContent().build();
    }
}
