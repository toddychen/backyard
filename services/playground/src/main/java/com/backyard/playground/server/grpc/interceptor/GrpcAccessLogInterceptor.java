package com.backyard.playground.server.grpc.interceptor;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs one access log line per gRPC call and sets MDC fields for structured log
 * output, mirroring {@code AccessLogFilter} for REST.
 *
 * <p>
 * MDC fields (emitted as top-level JSON fields via logstash-logback-encoder):
 * <ul>
 * <li>{@code grpc.method} — full method path, e.g.
 * {@code /com.backyard.playground.grpc.sport.Sport/GetTeamGames}
 * <li>{@code grpc.status} — gRPC status code name, e.g. {@code OK}
 * <li>{@code grpc.duration_ms} — wall time in milliseconds
 * </ul>
 *
 * <p>
 * Registered outermost so total duration includes all other interceptors.
 */
@Component
public class GrpcAccessLogInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcAccessLogInterceptor.class);

    private static final String MDC_METHOD = "grpc.method";
    private static final String MDC_STATUS = "grpc.status";
    private static final String MDC_DURATION_MS = "grpc.duration_ms";

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        String method = "/" + call.getMethodDescriptor().getFullMethodName();
        long start = System.currentTimeMillis();

        MDC.put(MDC_METHOD, method);

        return next.startCall(new SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long durationMs = System.currentTimeMillis() - start;
                String statusCode = status.getCode().name();

                MDC.put(MDC_STATUS, statusCode);
                MDC.put(MDC_DURATION_MS, String.valueOf(durationMs));
                try {
                    log.info("{} grpc {} {} {}ms locale={}",
                            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                            method,
                            statusCode,
                            durationMs,
                            MDC.get(GrpcLocaleInterceptor.MDC_LOCALE));
                } finally {
                    MDC.remove(MDC_METHOD);
                    MDC.remove(MDC_STATUS);
                    MDC.remove(MDC_DURATION_MS);
                }

                super.close(status, trailers);
            }
        }, headers);
    }
}
