package com.backyard.playground.server.grpc;

import com.backyard.playground.server.grpc.interceptor.GrpcAccessLogInterceptor;
import com.backyard.playground.server.grpc.interceptor.GrpcExceptionInterceptor;
import com.backyard.playground.server.grpc.interceptor.GrpcLocaleInterceptor;
import com.backyard.playground.server.grpc.service.GrpcEchoService;
import com.backyard.playground.server.grpc.service.GrpcSportService;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

@Configuration
public class GrpcServerConfig implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private final int port;
    private final GrpcEchoService grpcEchoService;
    private final GrpcSportService grpcSportService;
    private final GrpcExceptionInterceptor grpcExceptionInterceptor;
    private final GrpcLocaleInterceptor grpcLocaleInterceptor;
    private final GrpcAccessLogInterceptor grpcAccessLogInterceptor;
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    private Server server;
    private volatile boolean running;

    public GrpcServerConfig(
            @Value("${grpc.server.port:9090}") int port,
            GrpcEchoService grpcEchoService,
            GrpcSportService grpcSportService,
            GrpcExceptionInterceptor grpcExceptionInterceptor,
            GrpcLocaleInterceptor grpcLocaleInterceptor,
            GrpcAccessLogInterceptor grpcAccessLogInterceptor) {
        this.port = port;
        this.grpcEchoService = grpcEchoService;
        this.grpcSportService = grpcSportService;
        this.grpcExceptionInterceptor = grpcExceptionInterceptor;
        this.grpcLocaleInterceptor = grpcLocaleInterceptor;
        this.grpcAccessLogInterceptor = grpcAccessLogInterceptor;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(port)
                    // One fresh virtual thread per call — keeps
                    // InheritableThreadLocal (ClientLocaleContextHolder)
                    // clean across requests with no manual cleanup needed.
                    .executor(Executors.newVirtualThreadPerTaskExecutor())

                    // Interceptors are applied in reverse registration order —
                    // last registered runs first (outermost). Effective order:
                    //   Locale → AccessLog → Exception → service
                    //
                    // Locale outermost: resolves Accept-Language and sets
                    // ClientLocaleContextHolder + MDC "locale" before any other
                    // interceptor runs, so the access log can include the locale.
                    //
                    // AccessLog second: logs the access line after locale is set,
                    // capturing total duration including exception handling.
                    //
                    // Exception innermost: catches exceptions from service methods
                    // and maps them to gRPC Status codes. Closest to the service
                    // so no other interceptor swallows exceptions first.
                    .intercept(grpcExceptionInterceptor)
                    .intercept(grpcAccessLogInterceptor)
                    .intercept(grpcLocaleInterceptor)
                    .addService(grpcEchoService)
                    .addService(grpcSportService)
                    .addService(healthStatusManager.getHealthService())
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
            running = true;
            healthStatusManager.setStatus("com.backyard.playground.echo.Echo", ServingStatus.SERVING);
            healthStatusManager.setStatus("com.backyard.playground.sport.Sport", ServingStatus.SERVING);
            log.info("gRPC server started on port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("Shutting down gRPC server on port {}", port);
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
