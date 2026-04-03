package com.backyard.playground.exception;

public class ServiceUnavailableException extends BaseException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
