package com.backyard.playground.server.rest.controller;

import org.springframework.http.ResponseEntity;

public abstract class BaseController {

    /** Extracts the raw token from a {@code Bearer <token>} header. */
    protected String bearerToken(String authHeader) {
        return authHeader.startsWith("Bearer ") ? authHeader.substring(7) : "";
    }

    protected ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    protected ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(message);
    }
}
