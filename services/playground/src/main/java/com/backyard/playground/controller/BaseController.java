package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;

public abstract class BaseController {

    protected ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    protected ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(message);
    }
}
