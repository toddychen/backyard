package com.backyard.playground.client.grpc;

import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;
import com.backyard.playground.exception.UnauthorizedException;

import io.grpc.StatusRuntimeException;

import java.util.function.Supplier;

/**
 * Base class for all outbound gRPC client implementations.
 *
 * <p>
 * Subclasses wrap every stub call with {@link #rpc}, which handles error
 * mapping in one place: any {@link StatusRuntimeException} thrown by the stub
 * is caught here and translated to a typed domain exception
 * ({@link NotFoundException}, {@link ServiceUnavailableException}, etc.) that
 * {@code GlobalExceptionHandler} converts to a clean API response. Subclasses
 * do not need try/catch in individual methods.
 */
public abstract class BaseClient {

    /**
     * Executes a gRPC stub call, mapping any {@link StatusRuntimeException} to a
     * typed domain exception.
     */
    protected <T> T rpc(Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            throw mapStatus(e);
        }
    }

    private static RuntimeException mapStatus(StatusRuntimeException e) {
        String description = e.getStatus().getDescription() != null
                ? e.getStatus().getDescription()
                : e.getStatus().getCode().name().toLowerCase().replace('_', ' ');
        return switch (e.getStatus().getCode()) {
        case NOT_FOUND -> new NotFoundException("upstream not found: " + description, e);
        case INVALID_ARGUMENT -> new BadRequestException("upstream bad request: " + description, e);
        case UNAUTHENTICATED -> new UnauthorizedException("upstream authentication failed: " + description, e);
        case PERMISSION_DENIED -> new ForbiddenException("upstream access denied: " + description, e);
        case UNAVAILABLE -> new ServiceUnavailableException("upstream unavailable: " + description, e);
        case DEADLINE_EXCEEDED -> new ServiceUnavailableException("upstream deadline exceeded: " + description, e);
        case RESOURCE_EXHAUSTED -> new ServiceUnavailableException("upstream rate limit exceeded: " + description, e);
        default -> new ServiceUnavailableException("upstream error " + e.getStatus().getCode() + ": " + description, e);
        };
    }
}
