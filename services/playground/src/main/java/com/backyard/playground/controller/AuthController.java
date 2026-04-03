package com.backyard.playground.controller;

import com.backyard.playground.service.AuthService;
import com.backyard.playground.service.AuthService.TokenPair;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/{version}/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record RegisterRequest(String email, String password) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken) {
    }

    /** POST /api/v1/auth/register */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody RegisterRequest req) {
        authService.register(req.email(), req.password());
        return ResponseEntity.status(201).build();
    }

    /** POST /api/v1/auth/login */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req) {
        TokenPair tokens = authService.login(req.email(), req.password());
        return ResponseEntity.ok(new TokenResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    /** POST /api/v1/auth/token/refresh */
    @PostMapping("/token/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        TokenPair tokens = authService.refresh(req.refreshToken());
        return ResponseEntity.ok(new TokenResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    /** POST /api/v1/auth/logout */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody RefreshRequest req,
            @RequestHeader("Authorization") String authHeader,
            @AuthenticationPrincipal UUID userId) {
        String rawAccessToken = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : "";
        authService.logout(req.refreshToken(), rawAccessToken);
        return ResponseEntity.noContent().build();
    }
}
