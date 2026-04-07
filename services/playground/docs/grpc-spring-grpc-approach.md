# gRPC Integration via Spring gRPC (Future Approach)

> **Status: Deferred.** Spring gRPC 1.1.0-M1 requires Spring Boot 4.1.x, which has no stable
> release as of April 2026. Revisit when Boot 4.1.x goes GA (expected mid–late 2026).
>
> The current implementation uses pure `io.grpc:grpc-java` — see the plan doc and journal notes.

---

## Overview

Spring gRPC is the official Spring project for gRPC integration, built on top of grpc-java.
It provides Spring-native abstractions: auto-configuration, annotation-driven service registration,
`application.properties`-driven channel config, and built-in Micrometer observability.

**Version:** `org.springframework.grpc:spring-grpc-dependencies:1.1.0-M1`
**Requires:** Spring Boot 4.1.x

---

## Maven Setup

### BOM Import (`pom.xml` dependencyManagement)

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.grpc</groupId>
      <artifactId>spring-grpc-dependencies</artifactId>
      <version>1.1.0-M1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Dependencies

```xml
<!-- Spring gRPC auto-configuration -->
<dependency>
  <groupId>org.springframework.grpc</groupId>
  <artifactId>spring-grpc-spring-boot-autoconfigure</artifactId>
</dependency>

<!-- Use shaded Netty to avoid conflicts -->
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-netty-shaded</artifactId>
</dependency>

<!-- Health service (K8s grpcProbe + grpcurl list) -->
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-services</artifactId>
</dependency>
```

Versions of grpc-java and protobuf-java are managed by the Spring gRPC BOM.

### Milestone Repository

```xml
<repositories>
  <repository>
    <id>spring-milestones</id>
    <name>Spring Milestones</name>
    <url>https://repo.spring.io/milestone</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

### Build Plugin (unchanged from pure grpc-java approach)

`io.github.ascopes:protobuf-maven-plugin` with `protoc-gen-grpc-java`.

---

## Server Side

### Service Implementation

Spring gRPC auto-registers any `@Service` bean extending a generated `*ImplBase`. No manual
`ServerBuilder` or `SmartLifecycle` needed.

```java
@Service
public class GrpcBinDemoService extends GrpcBinDemoGrpc.GrpcBinDemoImplBase {

    private final GrpcBinClient grpcBinClient;

    @Override
    public void echoUnary(EchoRequest req, StreamObserver<EchoReply> responseObserver) {
        EchoReply reply = grpcBinClient.echoUnary(req.getMessage());
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
```

### Server Configuration

```properties
spring.grpc.server.port=9090

# Sync Spring health indicators into gRPC health service
spring.grpc.server.health.actuator.health-indicator-paths=db,redis
```

### Virtual Thread Executor

```java
@Bean
ServerBuilderCustomizer<?> virtualThreadExecutor() {
    return builder -> builder.executor(Executors.newVirtualThreadPerTaskExecutor());
}
```

### Server Interceptors

Spring gRPC uses `@GlobalServerInterceptor` with `@Order` — no manual `ServerBuilder.intercept()`:

```java
@Bean @Order(1) @GlobalServerInterceptor
GrpcAccessLogInterceptor accessLog() { return new GrpcAccessLogInterceptor(); }

@Bean @Order(2) @GlobalServerInterceptor
GrpcLocaleInterceptor locale(LocaleResolver localeResolver) {
    return new GrpcLocaleInterceptor(localeResolver);
}

@Bean @Order(3) @GlobalServerInterceptor
GrpcJwtAuthInterceptor jwtAuth(JwtTokenService jwtTokenService, ...) { ... }
```

Per-service interceptors via `@GrpcService(interceptors = MyInterceptor.class)`.

### Exception Handling

```java
@Bean
GrpcExceptionHandler grpcExceptionHandler() {
    return new PlaygroundGrpcExceptionHandler();
}
```

`PlaygroundGrpcExceptionHandler` mirrors `GlobalExceptionHandler`, mapping typed exceptions to
gRPC status codes (`BadRequestException → INVALID_ARGUMENT`, etc.).

### Health & Reflection

Both auto-configured when `io.grpc:grpc-services` is on the classpath:
- Health: enabled when any `BindableService` bean exists
- Reflection: enabled automatically (allows `grpcurl list`)

---

## Client Side

### Channel Configuration (properties-driven)

```java
@Bean
GrpcBinGrpc.GrpcBinBlockingStub grpcBinStub(GrpcChannelFactory channels) {
    return GrpcBinGrpc.newBlockingStub(channels.createChannel("grpcbin"));
}
```

```properties
spring.grpc.client.channels.grpcbin.address=grpcb.in:9001
spring.grpc.client.channels.grpcbin.negotiation-type=PLAINTEXT
```

`GrpcChannelFactory` manages channel lifecycle (singleton, shutdown on context close).
No manual `@Bean(destroyMethod = "shutdown")` needed.

### Auto-Scanning Stubs (`@ImportGrpcClients`)

For services with many stubs, Spring gRPC can auto-scan and create stub beans:

```java
@ImportGrpcClients(basePackageClasses = PlaygroundApplication.class)
@SpringBootApplication
public class PlaygroundApplication { ... }
```

Requires `spring.grpc.client.default-channel.address` to be set.

### Client Interceptors

```java
@Bean @Order(1) @GlobalClientInterceptor
ClientInterceptor grpcClientLogger() { return new GrpcClientLoggingInterceptor(); }
```

Per-channel interceptors via `ChannelBuilderOptions`:
```java
channels.createChannel("grpcbin", ChannelBuilderOptions.defaults()
    .withInterceptors(List.of(new BearerTokenAuthenticationInterceptor(...))));
```

### Resilience

Same pattern as pure grpc-java: gRPC stubs are classes, not interfaces. Define a Java interface
per upstream, implement delegating to `BlockingStub`, wrap with `wrapWithResilience` from
`ClientConfig`. The Spring gRPC approach does not change this pattern.

---

## Observability

Spring gRPC auto-configures a Micrometer observation interceptor for both server and client when
`spring-boot-starter-actuator` is on the classpath (already present). This provides:

- gRPC server/client spans in Jaeger (via the existing OTel bridge)
- Micrometer metrics at `/actuator/prometheus`:
  - `grpc_server_processing_duration_seconds{grpc_method, grpc_service, grpc_status_code}`
  - `grpc_client_attempt_duration_seconds{...}`

No `GrpcOpenTelemetry.registerGlobal()` call needed — Spring gRPC handles this automatically.

---

## Testing

Spring gRPC provides `@AutoConfigureInProcessTransport` for clean integration tests:

```java
@SpringBootTest
@AutoConfigureInProcessTransport
class GrpcBinDemoServiceTest {

    @Autowired
    GrpcBinDemoGrpc.GrpcBinDemoBlockingStub stub;

    @Test
    void echoUnary_returnsExpectedReply() {
        EchoReply reply = stub.echoUnary(EchoRequest.newBuilder().setMessage("hi").build());
        assertThat(reply.getMessage()).isEqualTo("hi");
    }
}
```

The in-process server replaces the real Netty server — no port conflicts in CI.

---

## Key Differences vs Pure grpc-java

| Concern | Pure grpc-java | Spring gRPC |
|---|---|---|
| Server lifecycle | `GrpcServerConfig` + `SmartLifecycle` (~50 lines) | Auto-configured |
| Service registration | `ServerBuilder.addService(...)` | `@Service` on `ImplBase` |
| Interceptor registration | `ServerBuilder.intercept(...)` | `@GlobalServerInterceptor` |
| Exception handling | Custom interceptor wrapping `onHalfClose()` | `GrpcExceptionHandler` bean |
| Health service | Manual `HealthStatusManager` registration | Auto-configured |
| Reflection service | Manual `ProtoReflectionService` registration | Auto-configured |
| Channel config | Code only (`ManagedChannelBuilder`) | `application.properties` |
| Channel lifecycle | `@Bean(destroyMethod = "shutdown")` | Managed by `GrpcChannelFactory` |
| Observability | `GrpcOpenTelemetry.registerGlobal()` call | Auto-configured |
| Testing | Manual in-process setup | `@AutoConfigureInProcessTransport` |

Spring gRPC eliminates most of the boilerplate while keeping the same grpc-java semantics.
Migration from pure grpc-java to Spring gRPC is primarily replacing `@Bean` configuration with
annotations — no changes to service logic, proto definitions, or interceptor implementations.
