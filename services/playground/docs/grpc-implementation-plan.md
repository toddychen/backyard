# gRPC Integration Design — Playground Service

## Context

The playground service is Spring Boot 4.0.4 / Java 21, currently REST-only (Tomcat + Spring MVC).

**Scope:**
1. **New gRPC-only endpoints** (server side) — not mirroring existing REST APIs. New capabilities
   exposed exclusively via gRPC (e.g., a streaming leaderboard or server-push events).
2. **Outbound gRPC client demo** — call the public `grpcb.in:9001` API (the gRPC equivalent of
   httpbin) to demonstrate client-side usage with no auth required.

REST endpoints remain unchanged. REST and gRPC coexist on separate ports (8080 / 9090).

**Why not Spring gRPC:** Spring gRPC 1.1.0-M1 requires Spring Boot 4.1.x, which has no stable
release yet. The Spring gRPC approach is documented separately in
[`grpc-spring-grpc-approach.md`](grpc-spring-grpc-approach.md) for future reference.

**Chosen approach: pure `io.grpc:grpc-java`**, manually wired as Spring `@Bean`s — zero
framework-compatibility risk, same pattern as `ClientConfig.java`.

---

## Architecture Overview

```
Pod
├── Tomcat  (port 8080)  ← Spring MVC REST (unchanged)
└── Netty   (port 9090)  ← grpc-java server, managed as SmartLifecycle bean

Shared Spring beans
  GrpcBinClient        ← outbound gRPC client to grpcb.in
  ClientLocaleContextHolder  ← reused via GrpcLocaleInterceptor
  ObservationRegistry  ← unified traces: REST + gRPC
```

Both servers run in the **same JVM process** — one `java` command, one Spring ApplicationContext,
one pod. "Separate ports" refers only to TCP port numbers, not separate deployments.

---

## New gRPC Endpoints (server side)

**`GrpcBinDemoService`** — proxies calls through to `grpcb.in`, demonstrating both server exposure
and outbound client in one service:
- `EchoUnary(EchoRequest) → EchoReply`
- `EchoServerStream(EchoRequest) → stream EchoReply` — demonstrates server streaming

---

## Proto File Structure

```
services/playground/src/main/proto/
  com/backyard/playground/
    grpcbin/grpcbin_demo.proto
```

Conventions:
```protobuf
syntax = "proto3";
package com.backyard.playground.grpcbin.v1;
option java_package = "com.backyard.playground.grpcbin.v1";
option java_outer_classname = "GrpcBinDemoProto";
option java_multiple_files = true;
```

---

## Maven Dependencies

```xml
<properties>
  <grpc.version>1.73.0</grpc.version>
  <protobuf.version>4.31.1</protobuf.version>
</properties>

<!-- gRPC transport (shaded Netty avoids version conflicts) -->
<dependency>
  <groupId>io.grpc</groupId><artifactId>grpc-netty-shaded</artifactId><version>${grpc.version}</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId><artifactId>grpc-protobuf</artifactId><version>${grpc.version}</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId><artifactId>grpc-stub</artifactId><version>${grpc.version}</version>
</dependency>
<!-- Health service (K8s grpcProbe + grpcurl list) -->
<dependency>
  <groupId>io.grpc</groupId><artifactId>grpc-services</artifactId><version>${grpc.version}</version>
</dependency>
<!-- Protobuf runtime -->
<dependency>
  <groupId>com.google.protobuf</groupId>
  <artifactId>protobuf-java</artifactId><version>${protobuf.version}</version>
</dependency>
<!-- Required by generated stubs on Java 9+ -->
<dependency>
  <groupId>javax.annotation</groupId>
  <artifactId>javax.annotation-api</artifactId><version>1.3.2</version>
</dependency>
<!-- OpenTelemetry gRPC instrumentation -->
<dependency>
  <groupId>io.grpc</groupId><artifactId>grpc-opentelemetry</artifactId><version>${grpc.version}</version>
</dependency>
```

Build plugin: `io.github.ascopes:protobuf-maven-plugin` with `protoc-gen-grpc-java`.
Generated sources: `target/generated-sources/protobuf/java/` and `.../grpc-java/`.

---

## Server Config (`GrpcServerConfig.java`)

Manual `@Configuration` bean — analogous to `ClientConfig.java`:

```java
@Configuration
public class GrpcServerConfig implements SmartLifecycle {

    private Server server;

    @Bean
    public HealthStatusManager healthStatusManager() {
        return new HealthStatusManager();
    }

    @PostConstruct
    void start() throws IOException {
        server = Grpc.newServerBuilderForPort(9090, InsecureServerCredentials.create())
            .executor(Executors.newVirtualThreadPerTaskExecutor())  // ← virtual threads
            .addService(grpcBinDemoService)
            .addService(healthStatusManager.getHealthService())
            .addService(ProtoReflectionService.newInstance())       // ← grpcurl list
            .intercept(grpcAccessLogInterceptor)
            .intercept(grpcLocaleInterceptor)
            .build()
            .start();
    }

    @PreDestroy
    void stop() { server.shutdown(); }
}
```

**Virtual thread executor** ensures `InheritableThreadLocal` (`ClientLocaleContextHolder`)
works identically to the REST path.

---

## Server Interceptors

| Servlet filter | gRPC `ServerInterceptor` |
|---|---|
| `AccessLogFilter` | `GrpcAccessLogInterceptor` — wraps `ServerCall.close()` via `ForwardingServerCall`; sets MDC `grpc.method`, `grpc.status`, `grpc.duration_ms`; logs access line |
| `LocaleFilter` | `GrpcLocaleInterceptor` — reads `accept-language` from gRPC `Metadata`; reuses extracted `LocaleResolver` utility; sets `ClientLocaleContextHolder` + MDC `locale`; clears in listener `onComplete`/`onCancel` |

No auth interceptor — gRPC endpoints are public (unauthenticated).

For streaming calls, also propagate locale via `Context.Key<ClientLocaleContext>` (gRPC `Context`
propagates across streaming callbacks where `InheritableThreadLocal` does not).

---

## Exception Handling

`GrpcExceptionHandlingInterceptor` wraps `ServerCallListener.onHalfClose()`, catches exceptions,
and delegates to `GrpcExceptionMapper`:

| App exception | gRPC Status |
|---|---|
| `BadRequestException` | `INVALID_ARGUMENT` |
| `NotFoundException` | `NOT_FOUND` |
| `UnauthorizedException` | `UNAUTHENTICATED` |
| `ForbiddenException` | `PERMISSION_DENIED` |
| `ConflictException` | `ALREADY_EXISTS` |
| `ServiceUnavailableException` | `UNAVAILABLE` |
| `CallNotPermittedException` | `UNAVAILABLE` |
| `Exception` (unhandled) | `INTERNAL` (no stack trace leaked) |

---

## Outbound gRPC Client (`grpcb.in`)

**Target:** `grpcb.in:9001` — plaintext, no auth.
Verify: `grpcurl -plaintext grpcb.in:9001 list`

Channel wired in `GrpcClientConfig.java` (analogous to `ClientConfig.java`):

```java
@Bean(destroyMethod = "shutdown")
public ManagedChannel grpcBinChannel() {
    return ManagedChannelBuilder.forTarget("grpcb.in:9001").usePlaintext().build();
}

@Bean
public GrpcBinGrpc.GrpcBinBlockingStub grpcBinStub(ManagedChannel grpcBinChannel) {
    return GrpcBinGrpc.newBlockingStub(grpcBinChannel);
}
```

**Resilience:** gRPC stubs are classes, not interfaces — cannot proxy directly.
Define `GrpcBinClient` interface, implement delegating to `BlockingStub`, wrap with existing
`wrapWithResilience` from `ClientConfig`. Circuit breaker key: `grpcbin-echoUnary`.

Add `GrpcRetryPredicate` that retries `UNAVAILABLE` / `DEADLINE_EXCEEDED`, not
`INVALID_ARGUMENT` / `NOT_FOUND` / `UNAUTHENTICATED`.

**Stub type:** `BlockingStub` for unary calls (safe on virtual threads). Async stub with
`StreamObserver` for server-streaming from `grpcb.in`.

---

## Observability

Add `io.grpc:grpc-opentelemetry`. Wire to the existing `OpenTelemetry` bean once at startup:

```java
@Bean
GrpcOpenTelemetry grpcOpenTelemetry(OpenTelemetry openTelemetry) {
    GrpcOpenTelemetry g = GrpcOpenTelemetry.newBuilder().sdk(openTelemetry).build();
    g.registerGlobal();   // auto-instruments all channels + servers
    return g;
}
```

- Server calls appear as child spans under the parent HTTP request trace in Jaeger
- Client calls to `grpcb.in` also traced
- gRPC metrics (`grpc_server_processing_duration_seconds`, etc.) appear in `/actuator/prometheus`
  via Micrometer's existing OTel bridge — no new scrape config needed
- Suppress `grpc.health.v1.Health/Check` from tracing (mirrors `ActuatorObservationFilter`)

---

## Kubernetes / Helm Changes

### Service (`service.yaml`)
```yaml
- name: grpc
  port: 9090
  targetPort: 9090
  protocol: TCP
  appProtocol: grpc
```

### Deployment
Add `containerPort: 9090` and `grpc.port: 9090` in `values.yaml`.

### Health Probes
Keep HTTP actuator probes as primary. Optionally add `grpcProbe` (K8s 1.24+):
```yaml
readinessProbe:
  grpc:
    port: 9090
  initialDelaySeconds: 15
  periodSeconds: 5
```

---

## Implementation Sequence

**Phase 1 — Foundation**
1. Add Maven deps + `protobuf-maven-plugin` to `pom.xml`
2. Write `grpcbin_demo.proto` in `src/main/proto/`
3. `./mvnw generate-sources` — verify generated classes
4. Create `GrpcServerConfig.java` with virtual thread executor, health + reflection services
5. Create stub `GrpcBinDemoService.java` returning hardcoded reply
6. Start: `grpcurl -plaintext localhost:9090 list` confirms service is registered

**Phase 2 — Outbound gRPC client**
7. Create `GrpcClientConfig.java`, `ManagedChannel` + `BlockingStub` beans for `grpcb.in`
8. Define `GrpcBinClient` interface + impl, wire with `wrapWithResilience` + `GrpcRetryPredicate`
9. Implement `GrpcBinDemoService` to proxy through to `grpcb.in`

**Phase 3 — Cross-cutting interceptors**
10. Extract `LocaleResolver` utility from `LocaleFilter` (shared static method)
11. Create `GrpcAccessLogInterceptor`, `GrpcLocaleInterceptor`, `GrpcExceptionHandlingInterceptor`
12. Register all in `GrpcServerConfig`

**Phase 4 — Observability**
13. Add `grpc-opentelemetry`, wire `GrpcOpenTelemetry` bean
14. Verify traces in Jaeger, gRPC metrics in `/actuator/prometheus`

**Phase 5 — Kubernetes** (when deploying)
15. Update Helm `service.yaml`, `deployment.yaml`, `values*.yaml`

---

## Critical Files

- `src/main/java/.../client/config/ClientConfig.java` — `wrapWithResilience` pattern for `GrpcClientConfig`
- `src/main/java/.../filter/LocaleFilter.java` — BCP 47 resolution to extract as shared `LocaleResolver`
- `src/main/java/.../controller/GlobalExceptionHandler.java` — exception mapping table for `GrpcExceptionMapper`
- `infra/helm/playground/templates/deployment.yaml` — add port 9090, grpcProbe
- `pom.xml` — deps + build plugin

---

## Verification

1. `./mvnw generate-sources` — generated classes in `target/generated-sources/protobuf/`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` — Tomcat (8080) + Netty (9090) both start
3. `grpcurl -plaintext localhost:9090 list` — lists registered services
4. `grpcurl -plaintext -d '{"message":"hi"}' localhost:9090 ...GrpcBinDemoService/EchoUnary`
5. `curl localhost:8080/api/v1/dory/reminders` — REST unaffected
6. Jaeger: gRPC calls appear as child spans
7. `curl localhost:8080/actuator/prometheus | grep grpc` — gRPC metrics present
