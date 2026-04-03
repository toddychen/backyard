package com.backyard.playground.exception;

/**
 * Base class for all application exceptions. Supports standard Java exception
 * chaining via {@code
 * cause} — when thrown from an upstream API call, the cause carries the raw
 * upstream response so {@code GlobalExceptionHandler} can log it server-side
 * without exposing it to the caller.
 */
public class BaseException extends RuntimeException {

    public BaseException(String message) {
        super(message);
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
