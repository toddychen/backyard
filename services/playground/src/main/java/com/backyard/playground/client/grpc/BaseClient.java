package com.backyard.playground.client.grpc;

import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;
import com.backyard.playground.exception.UnauthorizedException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * Base class for all outbound gRPC client implementations.
 *
 * <p>
 * Subclasses wrap every stub call with {@link #rpc}, which applies Resilience4j
 * circuit breaker and retry, then maps any {@link StatusRuntimeException} to a
 * typed domain exception ({@link NotFoundException},
 * {@link ServiceUnavailableException}, etc.) that
 * {@code GlobalExceptionHandler} converts to a clean API response. Subclasses
 * do not need try/catch or resilience wiring in individual methods.
 *
 * <p>
 * Retry is only attempted on transient gRPC errors (UNAVAILABLE,
 * DEADLINE_EXCEEDED). Non-transient errors (INVALID_ARGUMENT, NOT_FOUND, etc.)
 * are mapped to domain exceptions immediately without retrying.
 *
 * <p>
 * Circuit breaker instances are keyed as {@code "<clientName>-<methodName>"}
 * (e.g. {@code "google_language-analyzeSentiment"}), giving each endpoint
 * independent breaker state. Retry instances are keyed as
 * {@code "<clientName>"} so all methods on a client share the same retry
 * configuration.
 *
 * <p>
 * Add entries to {@code application.properties} to configure per-endpoint
 * circuit breakers:
 *
 * <pre>
 * resilience4j.circuitbreaker.instances.google_language-analyzeSentiment
 *     .minimum-number-of-calls=5
 * resilience4j.circuitbreaker.instances.google_language-analyzeSentiment
 *     .sliding-window-size=10
 * resilience4j.circuitbreaker.instances.google_language-analyzeSentiment
 *     .wait-duration-in-open-state=30s
 * </pre>
 */
public abstract class BaseClient {

    // Retries only on transient gRPC errors. Max-attempts and backoff
    // are intentionally hardcoded here — gRPC retries are short-circuit
    // retries for network blips, not business-level retry policies.
    private static final RetryConfig GRPC_RETRY_CONFIG = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryOnException(e -> e instanceof StatusRuntimeException sre && isTransient(sre))
            .build();

    private final CircuitBreakerRegistry cbRegistry;
    private final RetryRegistry retryRegistry;

    protected BaseClient(CircuitBreakerRegistry cbRegistry, RetryRegistry retryRegistry) {
        this.cbRegistry = cbRegistry;
        this.retryRegistry = retryRegistry;
    }

    /**
     * Executes a gRPC stub call with circuit breaker and retry, mapping any
     * {@link StatusRuntimeException} to a typed domain exception.
     *
     * @param clientName base client name (e.g. {@code "google_language"}) — used as
     *                   the retry key
     * @param methodName method name (e.g. {@code "analyzeSentiment"}) — combined
     *                   with clientName as the circuit breaker key
     */
    /**
     * Executes a gRPC stub call with circuit breaker and retry, mapping any
     * {@link StatusRuntimeException} to a typed domain exception.
     *
     * @param clientName base client name (e.g. {@code "google_language"}) — used as
     *                   both the retry key and the circuit breaker key
     */
    /**
     * Executes a gRPC stub call with circuit breaker and retry, mapping any
     * {@link StatusRuntimeException} to a typed domain exception. The circuit
     * breaker is keyed as {@code "<clientName>"}.
     *
     * @param clientName base client name (e.g. {@code "grpcbin"})
     */
    protected <T> T rpc(String clientName, Supplier<T> call) {
        return rpc(clientName, null, call);
    }

    /**
     * Executes a gRPC stub call with circuit breaker and retry, mapping any
     * {@link StatusRuntimeException} to a typed domain exception.
     *
     * @param clientName base client name (e.g. {@code "google_language"}) — used as
     *                   the retry key
     * @param methodName method name (e.g. {@code "analyzeSentiment"}) — when
     *                   non-empty, the circuit breaker is keyed as
     *                   {@code "<clientName>-<methodName>"}; when empty, keyed as
     *                   {@code "<clientName>"}
     */
    protected <T> T rpc(String clientName, String methodName, Supplier<T> call) {
        Retry retry = retryRegistry.retry(clientName, GRPC_RETRY_CONFIG);
        String cbKey = StringUtils.isBlank(methodName) ? clientName : clientName + "-" + methodName;
        CircuitBreaker cb = cbRegistry.circuitBreaker(cbKey);

        try {
            return cb.decorateSupplier(retry.decorateSupplier(call)).get();
        } catch (StatusRuntimeException e) {
            throw mapStatus(e);
        }
    }

    private static boolean isTransient(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
        case UNAVAILABLE, DEADLINE_EXCEEDED -> true;
        default -> false;
        };
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
        default -> new ServiceUnavailableException(
                "upstream error " + e.getStatus().getCode() + ": " + description, e);
        };
    }
}
