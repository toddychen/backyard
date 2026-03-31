package com.backyard.playground.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ConflictException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;
import com.backyard.playground.exception.UnauthorizedException;

import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(int status, String message, String errorId) {
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        String errorId = generateErrorId();
        log.warn("bad request: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.badRequest().body(new ErrorResponse(400, ex.getMessage(), errorId));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        String errorId = generateErrorId();
        log.warn("unauthorized: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(401, ex.getMessage(), errorId));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        String errorId = generateErrorId();
        log.warn("forbidden: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(403, ex.getMessage(), errorId));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        String errorId = generateErrorId();
        log.warn("not found: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, ex.getMessage(), errorId));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        String errorId = generateErrorId();
        log.warn("conflict: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(409, ex.getMessage(), errorId));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        String errorId = generateErrorId();
        log.error("service unavailable: errorId='{}'", errorId, ex.getCause());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(503, ex.getMessage(), errorId));
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(
            CallNotPermittedException ex) {
        String errorId = generateErrorId();
        log.warn("circuit open for '{}': errorId='{}'",
                ex.getCausingCircuitBreakerName(), errorId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(503,
                        "upstream service temporarily unavailable",
                        errorId));
    }

    // Catches unhandled RestClient failures — network errors, unexpected
    // content types, or any upstream response not mapped by a status handler.
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException ex) {
        String errorId = generateErrorId();
        log.warn("outbound call failed: errorId='{}'", errorId, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(503, "upstream service unavailable", errorId));
    }

    // Silently returns 404 for missing static resources — avoids polluting
    // logs with browser/tooling probes (e.g. Chrome DevTools, favicon.ico).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound() {
        return ResponseEntity.notFound().build();
    }

    // Catch-all safety net — covers NPE, decode errors, bugs, and anything
    // else not explicitly handled above. Logged as error since this should
    // never happen in normal operation.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String errorId = generateErrorId();
        log.error("unexpected error: errorId='{}'", errorId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "internal server error", errorId));
    }

    private String generateErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
