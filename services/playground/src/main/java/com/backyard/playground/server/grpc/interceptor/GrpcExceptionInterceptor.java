package com.backyard.playground.server.grpc.interceptor;

import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ConflictException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;
import com.backyard.playground.exception.UnauthorizedException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.client.RestClientException;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * gRPC counterpart of {@code GlobalExceptionHandler}.
 *
 * <p>
 * Wraps each call's {@code onHalfClose()} to catch app exceptions and map them
 * to gRPC {@link Status} codes, mirroring the REST exception mapping. Stack
 * traces are logged server-side but never leaked to the client.
 */
@Component
public class GrpcExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionInterceptor.class);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (StatusRuntimeException e) {
                    // Already a gRPC status — pass through as-is
                    call.close(e.getStatus(), e.getTrailers() != null ? e.getTrailers() : new Metadata());
                } catch (BadRequestException e) {
                    close(Status.INVALID_ARGUMENT, e, "bad request");
                } catch (UnauthorizedException e) {
                    close(Status.UNAUTHENTICATED, e, "unauthenticated");
                } catch (ForbiddenException e) {
                    close(Status.PERMISSION_DENIED, e, "forbidden");
                } catch (NotFoundException e) {
                    close(Status.NOT_FOUND, e, "not found");
                } catch (ConflictException e) {
                    close(Status.ALREADY_EXISTS, e, "conflict");
                } catch (ServiceUnavailableException e) {
                    close(Status.UNAVAILABLE, e, "service unavailable");
                } catch (RestClientException e) {
                    String errorId = errorId();
                    log.warn("outbound call failed: errorId='{}'", errorId, e);
                    call.close(Status.UNAVAILABLE.withDescription("upstream service unavailable [" + errorId + "]"),
                            new Metadata());
                } catch (CallNotPermittedException e) {
                    String errorId = errorId();
                    log.warn("circuit open for '{}': errorId='{}'", e.getCausingCircuitBreakerName(), errorId);
                    call.close(Status.UNAVAILABLE.withDescription("upstream temporarily unavailable [" + errorId + "]"),
                            new Metadata());
                } catch (Exception e) {
                    String errorId = errorId();
                    log.error("unexpected gRPC error: errorId='{}'", errorId, e);
                    // Do not leak exception message — return opaque error id only
                    call.close(Status.INTERNAL.withDescription("internal error [" + errorId + "]"), new Metadata());
                }
            }

            private void close(Status status, Exception e, String label) {
                String errorId = errorId();
                log.warn("{}: errorId='{}'", label, errorId, e.getCause());
                call.close(status.withDescription(e.getMessage() + " [" + errorId + "]"), new Metadata());
            }
        };
    }

    private static String errorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
