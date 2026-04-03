# TODO

## Distributed Tracing Visualization

Integrate a tracing visualization tool to analyze request flows, latency, and bottlenecks across services.

**What we have already:**
- `micrometer-tracing-bridge-otel` — generates traceId/spanId, already in logs
- `opentelemetry-exporter-otlp` — ready to export spans, just needs a backend configured

**What needs to be done:**
- Choose a visualization backend (options: Google Cloud Trace, Jaeger, Zipkin)
- Configure the OTLP exporter endpoint in `application.properties`
- Set up the backend (e.g. deploy Jaeger in cluster, or enable Google Cloud Trace in GCP)
- Verify traces appear end-to-end: incoming request → Feign outbound call → response

**Goal:**
- See full request timeline across services
- Identify latency bottlenecks (which service/call is slow)
- Debug issues by following a single traceId through the system

---

## Cache Layer

Implement a multi-level cache for incoming API responses and outgoing Feign call results.

**Cache levels:**
- **L1 (in-memory)** — Caffeine cache inside the JVM, zero latency, lost on restart. Good for hot data within a single pod.
- **L2 (distributed)** — Redis or Memcached as a shared cache across all pods, survives restarts. Good for expensive outbound API calls (e.g. pollen data, which only updates a few times per day).

**What to cache:**
- Outgoing Feign responses (e.g. Ambee pollen data — no need to call external API on every request)
- Potentially: computed/aggregated responses before they hit the controller

**What needs to be done:**
- Add Spring Cache (`spring-boot-starter-cache`) + Caffeine for L1
- Add Redis (`spring-boot-starter-data-redis`) for L2 when ready
- Annotate service methods with `@Cacheable`
- Define TTL per cache (e.g. pollen data: 2-4 hours)
- Deploy Redis in cluster or use GCP Memorystore

**Goal:**
- Reduce latency for repeated requests
- Reduce outbound API call volume (rate limit protection)
- Keep cache warm across pod restarts via L2

---

## Redis Health Check & Error Handling

Add proper Redis health check and graceful degradation when Redis is unavailable.

**What to implement:**
- Add Redis to the Spring Boot health check (`/actuator/health`) so readiness
  probe fails when Redis is unreachable — prevents traffic from reaching a pod
  that cannot use the cache
- Wrap Redis connection errors with a clear error log including context
  (which cache, which key, what operation)
- Decide startup behavior: if Redis is down at startup, should the service
  refuse to start or start degraded (cache miss on every request)?
  Current behavior is to start and throw on first cache access — consider
  making this explicit

**What needs to be done:**
- Configure `management.health.redis.enabled=true` and include in readiness group
- Add error wrapping in cache layer for Redis failures
- Define fallback behavior: fail fast vs degrade gracefully (bypass cache)

---

## Database Integration

Connect the service to one or more database backends depending on the use case.

**Database types to consider:**
- **MySQL / PostgreSQL (relational)** — structured data, user records, application state. Use Spring Data JPA + Hibernate.
- **Qdrant (vector DB)** — stores embeddings for AI/RAG use cases: semantic search, similarity lookup, retrieval-augmented generation. Has a Java client.
- **MongoDB (document)** — flexible schema for semi-structured data if relational doesn't fit.

**What needs to be done:**
- Add `spring-boot-starter-data-jpa` + MySQL/PostgreSQL driver for relational DB
- Store credentials in GCP Secret Manager (DB password, connection string)
- Add Qdrant Java client when building RAG/AI features
- Deploy DB: GCP Cloud SQL (managed MySQL/PostgreSQL) or self-hosted in cluster
- Define schema, entities, and repositories

**Goal:**
- Persist application data beyond pod lifetime
- Enable AI/RAG features with vector search via Qdrant
- Keep credentials out of code and config files via Secret Manager

---

## Resilience4j — Circuit Breaker & Rate Limiting

Protect the service from cascading failures and abusing upstream services using Resilience4j (the modern replacement for Hystrix).

**Patterns to implement:**
- **Circuit Breaker** — if an upstream call (e.g. Ambee, Google APIs) fails repeatedly, open the circuit and stop calling it for a cooldown period. Fail fast instead of piling up slow failing requests.
- **Rate Limiter** — cap how many calls per second we send to an upstream service, staying within their limits.
- **Retry** — automatically retry a failed call a limited number of times with backoff before giving up.
- **Fallback** — return a cached result or a graceful error response when the circuit is open.

**What needs to be done:**
- Add `resilience4j-spring-boot3` dependency
- Add `spring-cloud-starter-circuitbreaker-resilience4j` for Feign integration
- Annotate Feign client calls or service methods with `@CircuitBreaker`, `@RateLimiter`, `@Retry`
- Define thresholds in `application.properties` (failure rate %, wait duration, etc.)
- Expose circuit breaker state via `/actuator/health` for visibility

**Goal:**
- Prevent one slow/failing upstream from taking down the whole service
- Avoid getting rate-limited or banned by external APIs
- Degrade gracefully with fallback responses instead of throwing 500s

---

## Concurrency — Thread Pool & Parallel Outbound Calls

Configure request thread pools and implement parallel outbound calls for endpoints that fan out to multiple upstream services.

**What to implement:**
- **Thread pool tuning** — configure Tomcat's request thread pool size to control max concurrent requests the service handles
- **Parallel Feign calls** — when one endpoint needs data from multiple upstream APIs (e.g. pollen + weather + air quality), fire all calls concurrently instead of sequentially
- **Blocking join step** — use `CompletableFuture.allOf()` to fan out calls in parallel then block at the end waiting for all results before assembling the response

**Example pattern:**
```java
CompletableFuture<Pollen> pollen   = CompletableFuture.supplyAsync(() -> ambeeClient.getPollen(...));
CompletableFuture<Weather> weather = CompletableFuture.supplyAsync(() -> weatherClient.getWeather(...));

CompletableFuture.allOf(pollen, weather).join(); // block here until both finish

return new CombinedResponse(pollen.get(), weather.get());
```

**What needs to be done:**
- Configure a dedicated `ExecutorService` / `ThreadPoolTaskExecutor` bean for async tasks
- Wrap parallel Feign calls in `CompletableFuture.supplyAsync()` using that thread pool
- Handle partial failures (one call fails, others succeed) gracefully
- Tune thread pool size based on number of upstream calls and expected concurrency

**Goal:**
- Reduce response latency when an endpoint depends on multiple upstream APIs
- Avoid blocking the Tomcat request thread during long outbound calls
- Keep resource usage bounded with a properly sized thread pool

---

## Alerting & On-Call Paging

Set up alerting rules on log conditions and metrics to proactively notify developers when issues occur in production.

**What to alert on:**
- Spike in ERROR or WARN log rate
- High request latency (p99 exceeds threshold)
- Circuit breaker opening on an upstream service
- Pod crash / OOMKilled / repeated restarts
- Health check failing (liveness/readiness probe)

**Tooling options:**
- **Google Cloud Alerting** — built into GCP, can trigger on Cloud Logging log-based metrics or Cloud Monitoring metrics. Easiest starting point since logs are already in GCP.
- **Grafana Alerting** — pairs with Prometheus metrics and Loki logs, flexible rule configuration
- **PagerDuty / OpsGenie** — on-call rotation, escalation policies, integrates with most alerting tools

**What needs to be done:**
- Define log-based metrics in Google Cloud Logging (e.g. count of ERROR logs per minute)
- Create alerting policies in Google Cloud Monitoring with notification channels (email, Slack, PagerDuty)
- Define thresholds per alert (e.g. >10 errors/min → page, >50 errors/min → escalate)
- Set up a Slack webhook as a low-friction notification channel for non-urgent alerts

**Goal:**
- Discover production issues before users report them
- Get paged on critical failures, notified on warnings
- Reduce mean time to detection (MTTD)

---

## Log Aggregation & Querying (Low Priority)

Integrate a centralized log aggregation tool with querying capability for searching and analyzing logs across services.

**Current state:**
- Logs are structured JSON (via `logstash-logback-encoder`) and already flowing into Google Cloud Logging
- Google Cloud Logging UI is sufficient for now — supports basic filtering and querying

**Options to evaluate later:**
- **Google Cloud Logging** — already working, good enough for early stage. Supports log-based metrics and alerts.
- **Grafana Loki** — lightweight, pairs well with Grafana dashboards, self-hostable in cluster
- **Elastic Stack (ELK)** — powerful full-text search, Kibana UI, but heavier to operate
- **Splunk** — most feature-rich querying, expensive, better suited for larger scale

**What needs to be done (when ready):**
- Evaluate query and alerting needs as log volume grows
- If moving beyond Google Cloud Logging: deploy Loki or set up ELK
- Add log shipper (Fluentd or Fluent Bit) as a DaemonSet in the cluster to forward logs
- Set up dashboards and alerts (e.g. spike in ERROR logs, latency anomalies)

**Goal:**
- Full-text search across logs from all services
- Correlate logs by traceId across services
- Set up alerts on error rate or specific log patterns

---

## Localization

Add locale support so the service can return localized content and pass locale context to upstream APIs.

**What to implement:**
- Accept locale from incoming requests (e.g. `Accept-Language` header or `?locale=` query param)
- Propagate locale as a request param or header on outbound Feign calls (e.g. `lang`, `locale`, `Accept-Language`)
- Use locale for response formatting where applicable (dates, units, labels)

**What needs to be done:**
- Decide locale input strategy: `Accept-Language` header (standard) vs explicit query param
- Extract locale in a filter or interceptor and store in a request-scoped context (e.g. `ThreadLocal` or Spring `RequestContextHolder`)
- Add a Feign `RequestInterceptor` to forward locale to all outbound calls automatically
- Validate supported locales and fall back to a default (e.g. `en`)

**Goal:**
- Support multi-language responses without each service method needing to handle locale manually
- Ensure upstream APIs receive locale context for localized data

---

## API Documentation (Swagger / OpenAPI)

Add Swagger UI and OpenAPI spec generation so all API endpoints are self-documented and explorable in a browser.

**What to implement:**
- Integrate `springdoc-openapi` to auto-generate an OpenAPI 3 spec from controllers and DTOs
- Expose Swagger UI at `/swagger-ui.html` for interactive exploration
- Annotate controllers and models with `@Operation`, `@ApiResponse`, `@Schema` where auto-generation is insufficient

**What needs to be done:**
- Add `springdoc-openapi-starter-webmvc-ui` dependency to `pom.xml`
- Expose the Swagger UI and OpenAPI JSON endpoints (add to actuator exposure or a separate path)
- Review and tune generated spec: descriptions, response codes, example values
- Decide whether to include Swagger UI in stage/prod or only in dev/internal

**Goal:**
- Make the API self-documenting for developers and consumers
- Enable easy manual testing of endpoints via Swagger UI
- Generate an OpenAPI spec that can be shared or used for client code generation

---

## User Authentication & Authorization

Secure API endpoints with identity verification and access control.

**What to implement:**
- **Authentication** — verify who the caller is (JWT / OAuth2 / API key)
- **Authorization** — enforce what an authenticated caller is allowed to do
  (e.g. a user can only read/write their own Dory reminders)

**Options:**
- **Spring Security + JWT** — stateless auth via signed tokens. Client
  sends `Authorization: Bearer <token>` on every request. Service verifies
  signature and extracts claims (user ID, roles) without hitting a DB.
- **OAuth2 / OIDC (Google, Auth0, Keycloak)** — delegate identity to an
  external provider. Service validates the access token against the
  provider's JWKS endpoint. Good for user-facing flows.
- **API key** — simple for machine-to-machine calls. Key passed as a
  header, validated against a secret store. No expiry/rotation built in.

**What needs to be done:**
- Add `spring-boot-starter-security`
- Choose auth mechanism (JWT self-issued vs OIDC provider)
- Define a `SecurityFilterChain`: public paths (`/actuator/health`,
  `/dory/**` SPA assets) vs protected paths (`/api/**`)
- Extract caller identity from the token and enforce ownership checks
  (e.g. Dory endpoint: `owner` in path must match authenticated user ID)
- Store secrets (JWT signing key, OAuth2 client secret) in GCP Secret
  Manager

**Goal:**
- Prevent unauthorized access to personal data (Dory reminders)
- Provide a foundation for per-user data isolation as features grow

---

## Streaming Endpoints — SSE & WebSockets

Add support for long-lived, push-based endpoint types beyond standard
request/response REST.

### Server-Sent Events (SSE)

One-way server-to-client streaming over a persistent HTTP connection.
The standard protocol used by AI streaming APIs (OpenAI, Anthropic, etc.)
to push token chunks as they're generated.

**What to implement:**
- Expose endpoints that return `text/event-stream` using Spring's
  `SseEmitter` or reactive `Flux<ServerSentEvent<T>>`
- Stream AI model output tokens to the client as they arrive, instead of
  waiting for the full response
- Handle client disconnects and emitter timeouts gracefully

**What needs to be done:**
- Add an SSE endpoint returning `SseEmitter` (blocking) or use
  `spring-boot-starter-webflux` for reactive `Flux`-based streaming
- Wire an AI client (e.g. Anthropic SDK stream method) to emit tokens
  as `ServerSentEvent` objects
- Set appropriate timeouts and handle `IOException` on emitter completion
- Test with `curl -N` or a browser `EventSource` client

### WebSockets

Bi-directional, full-duplex messaging over a persistent connection.
Good for chat interfaces, real-time dashboards, or any use case where
the client also needs to send messages mid-stream.

**What to implement:**
- Expose a WebSocket endpoint using Spring's `@ServerEndpoint` or
  `WebSocketHandler`
- Handle connect, message, and disconnect lifecycle events
- Support a simple echo or chat pattern as a starting point

**What needs to be done:**
- Add `spring-boot-starter-websocket` dependency
- Configure a `WebSocketHandler` and register it at a path
- Decide on message format: raw text, JSON frames, or STOMP (if
  pub/sub fan-out is needed)
- Add STOMP + SockJS if browser fallback or topic broadcasting is needed

**Goal:**
- Learn the full range of HTTP endpoint patterns beyond REST
- Enable AI token-streaming responses to clients (SSE)
- Enable real-time bidirectional communication (WebSockets)

---

## gRPC Support

Add gRPC as a supported protocol for both inbound server endpoints and outbound
client calls, alongside the existing REST/Feign layer.

**What to implement:**
- **Inbound gRPC endpoints** — expose service methods as gRPC RPCs so callers
  can invoke them over HTTP/2 with protobuf-encoded messages
- **Outbound gRPC client calls** — call downstream services that expose gRPC
  APIs using a generated stub (the gRPC equivalent of a Feign client)

**What needs to be done:**
- Define `.proto` files for inbound service contracts
- Add `grpc-spring-boot-starter` (e.g. `net.devh:grpc-spring-boot-starter`)
  to `pom.xml` and configure the gRPC server port
- Implement `@GrpcService`-annotated classes that extend the generated server
  base classes
- Generate client stubs from `.proto` files for outbound calls; inject and use
  `@GrpcClient`-annotated stubs in service classes
- Configure TLS for gRPC connections in stage/prod
- Expose gRPC health check via the standard `grpc.health.v1.Health` service
- Decide on protobuf vs JSON transcoding (grpc-gateway or Spring's HTTP/gRPC
  bridge) if REST interoperability is needed

**Goal:**
- Support high-efficiency, strongly-typed service-to-service communication
- Learn both sides: serving gRPC traffic and consuming gRPC upstream APIs
- Establish patterns for proto contract ownership and code generation in the
  build pipeline

---

## Message Queue Service

Introduce a distributed message queue to decouple services, handle async workloads, and absorb traffic spikes.

**Use cases:**
- Async task processing (e.g. sending notifications, triggering background jobs)
- Decoupling producers from consumers so a slow consumer doesn't block the producer
- Buffering bursts of incoming work without dropping requests
- Event-driven communication between services

**Tooling options:**
- **Google Cloud Pub/Sub** — fully managed, integrates naturally with GCP, no infra to operate. Good starting point.
- **Kafka** — high-throughput, durable, supports replay. Industry standard for event streaming at scale. Heavier to operate (or use Confluent Cloud managed).
- **RabbitMQ** — traditional message broker, simpler than Kafka, good for task queues and RPC patterns. Self-hostable in cluster.

**What needs to be done:**
- Choose broker based on use case (Pub/Sub for simplicity, Kafka for scale/replay)
- Add Spring integration: `spring-cloud-gcp-pubsub` for Pub/Sub, `spring-kafka` for Kafka
- Define topics/queues and producer/consumer code
- Deploy broker in cluster or use managed service (Pub/Sub, Confluent Cloud)
- Handle dead-letter queues for failed messages

**Goal:**
- Decouple services for independent scaling and deployment
- Handle async workloads without blocking request threads
- Build foundation for event-driven architecture
