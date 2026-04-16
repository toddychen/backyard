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

## SSE — Explore Server-Sent Events with Example Endpoints

Build concrete SSE endpoints to understand the protocol hands-on, using an
LLM chat response as the primary real-world example.

**Example endpoints to implement:**

- **Echo stream** — accept a message, split it into words, and emit one word
  per event with a small delay. Simplest possible SSE to verify the plumbing.
- **LLM chat response stream** — call the Anthropic (or OpenAI) API in
  streaming mode and forward each token chunk as an SSE event to the client.
  Client renders tokens incrementally as they arrive, matching the ChatGPT UX.
- **Live progress stream** — emit periodic status events for a long-running
  background task (e.g. `{"status":"processing","pct":42}`), then a final
  `done` event when complete.

**What needs to be done:**
- Add endpoints returning `SseEmitter` (blocking) for the echo and progress
  examples
- Wire the Anthropic SDK streaming method to an `SseEmitter` or reactive
  `Flux<ServerSentEvent<String>>` for the LLM example
- Set event types (`event:` field) so the client can distinguish token chunks
  from control events (e.g. `done`, `error`)
- Handle client disconnect: catch `IOException` on emit and complete/release
  the emitter
- Test echo and progress with `curl -N`; test LLM stream in a browser with
  `EventSource` or a minimal HTML page

**Goal:**
- Understand SSE framing: `data:`, `event:`, `id:`, `retry:` fields
- Experience the LLM streaming UX end-to-end from API call to browser render
- Establish a reusable SSE pattern for any future push-notification feature

---

## WebSocket Chat Application

Build a minimal multi-user chat app as a concrete end-to-end example of the
WebSocket infrastructure, using STOMP over WebSocket for message routing.

**What to implement:**
- **Server** — STOMP message broker backed by Spring's in-memory broker or an
  external broker (RabbitMQ). Users subscribe to a topic (e.g. `/topic/chat`)
  and the server broadcasts messages to all subscribers.
- **Client** — simple browser UI (HTML + JS) using `sockjs-client` and
  `@stomp/stompjs` to connect, send messages, and display incoming messages
  in real time.

**What needs to be done:**
- Add `spring-boot-starter-websocket` and configure `WebSocketMessageBrokerConfigurer`
- Enable simple in-memory broker on `/topic`, set app destination prefix `/app`
- Add a `@MessageMapping("/chat.send")` controller method that broadcasts to
  `/topic/chat`
- Handle user join/leave events via `@EventListener` on
  `SessionConnectedEvent` / `SessionDisconnectEvent`
- Build a minimal HTML page: connect button, message input, scrolling message list
- Test with two browser tabs sending messages to each other

**Collaborative editing example:**
- Multi-user shared text editor where edits broadcast in real time to all connected clients
- Server receives edit operations (insert/delete at position) and fans them out to
  all subscribers on a document topic (e.g. `/topic/doc/{id}`)
- Client applies incoming ops to local state; start with last-write-wins, then explore
  OT (Operational Transformation) or CRDT approaches for conflict resolution
- Demonstrates per-document topic routing, user presence tracking, and ordered
  message delivery

**Goal:**
- Concrete working example of full-duplex WebSocket messaging end-to-end
- Learn STOMP topic fan-out, session lifecycle, and Spring's message broker config
- Reusable pattern for any future real-time feature (notifications, live updates)

---

## Testing Strategy

### REST Service Tests

**Unit tests**
- Service layer: mock clients, assert business logic (e.g. `DoryService`, `SportService`, `GameService`)
- Exception mapping: verify typed exceptions produce correct HTTP status codes via `GlobalExceptionHandler`
- Cache logic: verify `@Cacheable` hits/misses, dynamic TTL on `Expirable` types
- JWT: token creation, parsing, expiry, denylist check in `JwtTokenService`

**Integration tests (`@SpringBootTest`)**
- Controller layer: `MockMvc` tests for each endpoint — request/response shape, status codes, auth
- Auth flow: login → get token → call protected endpoint → logout → token denylist
- Dory CRUD: full lifecycle via HTTP against H2 in-memory DB
- Locale pipeline: `Accept-Language` header → resolved locale in response headers + `Content-Language`

**Client tests**
- Resilience4j: verify circuit breaker opens after threshold, retry fires on transient errors
- Error translation: HTTP 4xx/5xx from upstream → correct typed exception

**Contract / component tests**
- Spin up a WireMock stub for each external API (Ambee, Yahoo Sports, Google Pollen)
- Assert correct request headers (auth, locale, User-Agent) and response mapping

---

### gRPC Service Tests

**Unit tests**
- Service impl: call `GrpcBinDemoService` methods directly — assert `StreamObserver` receives correct
  messages and `onCompleted` is called
- Exception mapper: verify each `BaseException` subclass maps to the correct `Status` code

**Integration tests (`@SpringBootTest` + in-process channel)**
- Start the full Spring context with an in-process gRPC server (no real port)
- Call methods via a `BlockingStub` wired to the in-process channel
- Unary: assert reply message and index
- Server-streaming: collect all replies into a list, assert count and content
- Error cases: trigger exceptions in the service impl, assert `StatusRuntimeException` with correct code

**End-to-end tests (local)**
- Start the service with `dev` profile
- Call `grpcurl -plaintext localhost:2090 ...` and assert responses
- Test reflection: `grpcurl -plaintext localhost:2090 list` returns expected services

---

### Cross-cutting / Infrastructure Tests

- **Health probes**: `GET /actuator/health/liveness` and `/readiness` return 200 with correct status
- **Metrics**: `GET /actuator/prometheus` contains expected metric names after a request
- **Tracing**: verify `traceId` and `spanId` appear in log output for both REST and gRPC calls
- **Access log**: verify MDC fields (`http.method`, `http.status`, `grpc.method`, `grpc.status`) appear in logs

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

## Role-Based Internal Auth System

Explore and implement a service-to-service authentication and authorization system,
similar to Yahoo's Athenz — where services have identities and roles, and access to
internal APIs is gated by policy rather than shared secrets.

**Why this matters:**
- JWT-based user auth (current) handles human → service auth, but not service → service
- Internal gRPC endpoints need a way to verify the caller is a trusted service
- Role-based access allows fine-grained control (e.g. only `sport-client` can call `Sport/GetTeamGames`)

**Industrial options to explore:**
- **Athenz** (Yahoo open source) — role-based access control for services, certificate-based identity,
  supports both mTLS and token-based auth. Full RBAC policy engine.
- **SPIFFE/SPIRE** — CNCF standard for workload identity via X.509 SVIDs. Integrates with
  Istio, Envoy. Identity is bound to the workload (pod/service), not a secret.
- **Istio mTLS** — if running a service mesh, mTLS is automatic per-pod. Peer authentication
  policy controls which services can talk to which. Zero code changes needed.
- **OPA (Open Policy Agent)** — policy-as-code engine. Decouples policy from service code.
  Can authorize gRPC calls based on metadata, method, caller identity.
- **JWT service tokens** — simpler approach: issue short-lived JWTs to services (not users),
  validated by the same `JwtTokenService`. No new infrastructure, but no revocation.

**What needs to be done:**
- Evaluate options against complexity, infrastructure cost, and current GCP/k8s setup
- Prototype the chosen approach on the gRPC Sport endpoint as a pilot
- Define how service identity is established (cert, token, mesh identity)
- Implement a `GrpcAuthInterceptor` that enforces the policy

---

## WebRTC Connection

Add WebRTC support for real-time peer-to-peer communication between clients,
with a signaling server to coordinate connection establishment.

**What to implement:**
- **Signaling server** — exchange SDP offers/answers and ICE candidates between
  peers so they can negotiate a direct P2P connection. Typically implemented over
  WebSockets or SSE.
- **Client** — browser or native client that initiates/accepts WebRTC connections,
  opens data channels or media streams, and handles ICE negotiation.

**What needs to be done:**
- Implement a WebSocket-based signaling endpoint in the Spring Boot service
  (reuse the WebSocket infrastructure from the Streaming Endpoints TODO)
- Handle signaling message types: `offer`, `answer`, `ice-candidate`, `join`, `leave`
- Build a minimal JS/browser client using the browser WebRTC API (`RTCPeerConnection`)
- Test a basic data channel echo between two browser tabs via the signaling server
- Consider TURN/STUN server setup for NAT traversal (e.g. Google's public STUN,
  or deploy Coturn for TURN)

**Goal:**
- Learn the WebRTC handshake and ICE negotiation flow end-to-end
- Enable real-time P2P data or media streaming between clients
- Establish a signaling pattern reusable for future real-time features

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

---

## Subscription Data — Cassandra

Store subscription/event data in Apache Cassandra, suited for high-write,
time-series, and append-heavy workloads like subscription event streams.

**Why Cassandra:**
- Optimized for high write throughput and wide rows — ideal for recording
  subscription lifecycle events (created, renewed, cancelled, expired)
- Linear horizontal scalability with no single point of failure
- Time-series access patterns (fetch all events for a subscriber, range by
  time) map naturally to Cassandra partition + clustering key design
- Better fit than MySQL for append-only event logs at scale

**What needs to be done:**
- Add `spring-boot-starter-data-cassandra` to `pom.xml`
- Define keyspace and table schema: partition key on `subscriber_id`,
  clustering key on `event_time DESC` for efficient latest-first queries
- Create `@Table`-annotated entity and `CassandraRepository` interface
- Deploy Cassandra: self-hosted in cluster or use DataStax Astra (managed
  Cassandra on GCP)
- Store credentials (contact points, keyspace, username/password) in GCP
  Secret Manager

**Goal:**
- Learn Cassandra data modelling (partition key, clustering key, wide rows)
- Handle subscription event storage at scale without MySQL write bottlenecks
- Establish a pattern for append-heavy, time-series data alongside the
  existing relational (MySQL) and vector (Qdrant) stores

---

## Geospatial Index in Database

Add geospatial indexing to support location-based queries such as
"find points within radius", "nearest N locations", or bounding-box searches.

**Options by database:**
- **MySQL** — supports `SPATIAL INDEX` on `GEOMETRY`/`POINT` columns using
  the R-tree index. Queries via `ST_Distance_Sphere`, `ST_Within`,
  `MBRContains`. Good fit if already on MySQL and queries are moderate volume.
- **PostgreSQL + PostGIS** — the industry standard for geospatial SQL.
  PostGIS adds full GIS types (`geography`, `geometry`), operators, and
  functions. Supports complex spatial queries, projections, and indexing
  via GiST/SP-GiST indexes.
- **Elasticsearch** — `geo_point` and `geo_shape` types with `geo_distance`
  and `geo_bounding_box` queries. Good when geospatial search is combined
  with full-text or faceted search.
- **Redis** — `GEOADD` / `GEODIST` / `GEORADIUS` commands for lightweight
  proximity lookups entirely in memory. Simple but no complex polygon queries.

**What needs to be done:**
- Choose backend based on query complexity and existing DB stack
- Define schema: store `POINT(lng, lat)` column with a spatial index
- Add Spring Data repository methods using `@Query` with spatial functions
  (e.g. `ST_Distance_Sphere` for MySQL, PostGIS operators for PostgreSQL)
- Seed test data and verify index is used via `EXPLAIN`
- Deploy spatial-capable DB instance (MySQL with spatial support, or Cloud
  SQL PostgreSQL with PostGIS extension enabled)

**Goal:**
- Learn spatial indexing concepts (R-tree, GiST) and how databases
  accelerate proximity queries
- Enable location-aware features (e.g. find nearby points of interest,
  filter by radius)
- Understand trade-offs between relational spatial (PostGIS) vs in-memory
  (Redis) vs search-engine (Elasticsearch) approaches

---

## Notification System — App Version Filtering

Add `app_version` to the `push_destination` Cassandra schema and allow
event triggers to filter by app version (e.g. only send to `>= 5.1.0`).

---

## Collaborative Editing System

Build a real-time collaborative editing system similar to Google Docs or Figma,
where multiple users can edit the same document simultaneously and see each
other's changes instantly.

**Core problem — conflict resolution:**
Collaborative editing is fundamentally a distributed systems problem. When two
users type at the same position at the same millisecond, their edits conflict.
Two main approaches exist:

- **OT (Operational Transformation)** — used by Google Docs. Each edit is an
  operation (insert/delete at position). When two operations conflict, a
  transformation function adjusts the second operation relative to the first.
  Requires a central server to serialize and transform operations.
- **CRDT (Conflict-free Replicated Data Type)** — used by Figma, Notion. Data
  structure designed so that concurrent edits always merge deterministically
  without a transformation step. Works peer-to-peer; no central coordinator needed.
  Examples: Yjs, Automerge.

**What to implement:**
- **Document model** — a document is a sequence of characters or rich-text nodes,
  each with a unique identity (position + author + timestamp, or a CRDT identity).
- **Transport** — WebSocket per document session, broadcasting operations to all
  connected editors. Reuse the existing WebSocket infrastructure.
- **Server** — receives edit operations, applies conflict resolution, broadcasts
  the resolved operation to all other clients. Persists the document state.
- **Client** — applies incoming remote operations to the local editor state without
  disrupting the user's current cursor position.

**What needs to be done:**
- Choose conflict resolution strategy (OT vs CRDT — start with OT for simplicity)
- Define operation types: `insert(position, char)`, `delete(position)`,
  `retain(count)` (Delta format, as used by Quill and Quilljs)
- Implement server-side operation transformation and broadcasting
- Build a minimal rich-text editor client (or integrate Quill / Slate.js)
- Persist document snapshots + operation log to Cassandra (append-only operations,
  periodic snapshots to avoid replaying the full history on load)
- Handle presence: show other users' cursors and selections in real time

**Goal:**
- Understand the core distributed systems challenge behind collaborative editing
- Learn OT or CRDT from first principles
- Build a reusable real-time document sync pattern

---

## Redis Pub/Sub Internals

Investigate how Redis implements its pub/sub feature internally — what data
structures it uses, how message delivery works, and where its limits are.

**Questions to answer:**
- How does Redis route a published message to all subscribers? Does it maintain
  a registry of subscriber connections per channel?
- Is pub/sub delivery synchronous (inline with PUBLISH) or async (queued)?
- What happens to a slow subscriber — does Redis queue messages for it or drop them?
- How does `PSUBSCRIBE` (pattern subscribe) work internally vs `SUBSCRIBE`?
- Where does Redis pub/sub break down — what are the throughput and latency limits
  of a single Redis instance?
- How does Redis Cluster handle pub/sub — are messages broadcast to all nodes or
  routed to the owning shard?

**Resources to explore:**
- Redis source code — `pubsub.c` in the Redis GitHub repo
- Redis documentation on pub/sub guarantees and cluster behaviour
- Redis internals book / blog posts (e.g. antirez's writing on Redis design)
- Benchmarks: measure throughput and latency of PUBLISH under increasing subscriber count

**Goal:**
- Understand the delivery model deeply enough to reason about failure modes
  (dropped messages, slow consumers, cluster behaviour)
- Inform the decision of when to migrate from Redis pub/sub to Kafka

---

## Kafka Internals — Message Queue Implementation

Investigate how Kafka implements its message queue feature internally — the
storage model, replication protocol, consumer group coordination, and where its
guarantees come from.

**Questions to answer:**

- **Log storage** — Kafka stores messages in an append-only log on disk. How is
  the log structured (segments, index files, `.log` vs `.index` vs `.timeindex`)?
  How does Kafka find a message by offset without scanning the entire log?
- **Partitioning** — how does Kafka distribute messages across partitions? How
  does the producer choose which partition to write to (round-robin, key hash,
  custom partitioner)?
- **Replication** — how does the leader-follower replication protocol work? What
  is the ISR (In-Sync Replica) set and how does Kafka use it to decide when a
  write is committed? What happens when a follower falls behind?
- **Consumer groups** — how does Kafka coordinate partition assignment across
  consumers in a group? What is the Group Coordinator and how does rebalancing
  work? What triggers a rebalance (new consumer joins, consumer dies, partition
  count changes)?
- **Offset management** — consumers track their own position (offset) rather than
  the broker tracking it per-consumer. How are offsets committed — what is the
  difference between auto-commit and manual commit? What happens if a consumer
  crashes before committing?
- **Delivery guarantees** — how does Kafka achieve at-least-once, at-most-once,
  and exactly-once delivery? What is the idempotent producer and how does the
  transactional API work?
- **Retention and compaction** — Kafka retains messages for a configurable time
  or size window. What is log compaction and how does it differ from time-based
  retention? When would you use a compacted topic?
- **Performance** — why is Kafka fast? How does sequential disk I/O, zero-copy
  (`sendfile`), and batching contribute to its throughput?

**Resources to explore:**
- Kafka documentation — "Design" section covers the log, replication, and
  consumer group protocol in depth
- Kafka source code — `kafka/log/` for storage, `kafka/coordinator/` for group
  coordination
- "The Log" blog post by Jay Kreps (LinkedIn) — foundational reading on the
  append-only log abstraction
- Kafka: The Definitive Guide (O'Reilly) — chapters on internals and replication
- KIP (Kafka Improvement Proposals) for exactly-once semantics (KIP-98)

**Goal:**
- Understand why Kafka's durability and throughput guarantees are fundamentally
  stronger than Redis pub/sub
- Reason about consumer group rebalancing as a failure mode in the WebSocket
  fan-out topology (rebalance = brief gap in message delivery)
- Know when compacted topics are the right choice (e.g. "latest state" topics
  vs "event stream" topics)
- Inform production Kafka configuration decisions (replication factor, ISR,
  acks setting, retention policy)
