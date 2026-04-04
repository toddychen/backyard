# Playground Service — Architecture

**Stack:** Spring Boot 4.0.4 · Java 21 · Helm · KinD · GCP (Argo CD)

A personal API backend with sports data, weather/pollen, reminders (Dory),
and diagnostics. Runs across four deployment profiles: `dev` (local),
`home` (pm2 fat-jar), `kind` (local Kubernetes), `stage`/`prod` (GCP).

---

## Table of Contents

1. [API Layer](#1-api-layer)
2. [Services](#2-services)
3. [Outbound HTTP Clients](#3-outbound-http-clients)
4. [Resilience — Circuit Breaker + Retry](#4-resilience--circuit-breaker--retry)
5. [Cache](#5-cache)
6. [Concurrent Outbound Calls — FanOut](#6-concurrent-outbound-calls--fanout)
7. [Dory Reminders — Persistence](#7-dory-reminders--persistence)
8. [Structured Logging](#8-structured-logging)
9. [Distributed Tracing](#9-distributed-tracing)
10. [Redis Connection Monitor](#10-redis-connection-monitor)
11. [Observability Stack](#11-observability-stack)
12. [Infra — KinD Cluster](#12-infra--kind-cluster)
13. [Infra — Helm Chart](#13-infra--helm-chart)
14. [Infra — Argo CD (GitOps)](#14-infra--argo-cd-gitops)
15. [Deployment Profiles](#15-deployment-profiles)
16. [Secrets Management](#16-secrets-management)
17. [Locale Pipeline (i18n)](#17-locale-pipeline-i18n)
18. [API Documentation](#18-api-documentation)

---

## 1. API Layer

### Versioning

Spring Boot 4's native `ApiVersionConfigurer` extracts the version from path
segment index 1, scoped to `/api/**`:

```
/api/v1/weather/pollen?city=san-jose
     ^^ segment index 1
```

`WebMvcConfig` sets `setVersionRequired(false)` so `DoryUiController` can
serve `/dory/{owner}` without a version. Controllers declare
`version = "1+"` to accept v1 and above.

`@Configuration` — `WebMvcConfig` also applies a global `/api` prefix to
all `@RestController` mappings via `configurePathMatch`.

### Endpoints

| Method | Path | Controller | Notes |
|--------|------|-----------|-------|
| GET | `/api/v1/echo` | EchoController | `?message=&from=` |
| GET | `/api/v1/echo/hello` | EchoController | translated "Hello" demo |
| GET | `/api/v1/test/tracing` | TestController | returns traceId/spanId |
| GET | `/api/v1/weather/pollen` | WeatherController | `?city=san-jose` |
| GET | `/api/v1/sport/team/{id}/games` | SportController | `?next_x&last_x&parallel&cached` |
| GET | `/api/v1/sport/game/{id}/details` | SportController | |
| GET/POST | `/api/v1/dory/reminders` | DoryController | list, create |
| PUT/PATCH/DELETE | `/api/v1/dory/reminders/{id}` | DoryController | update, done, snooze, delete |
| GET | `/dory/{owner}` | DoryUiController | serves SPA `index.html` |

### Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps typed exceptions to
`ErrorResponse(status, message, errorId)` with a short UUID for correlation:

| Exception | HTTP |
|-----------|------|
| `BadRequestException` | 400 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `ServiceUnavailableException` | 503 |
| `CallNotPermittedException` | 503 (circuit open) |
| `RestClientException` | 503 (catch-all client failure) |
| `NoResourceFoundException` | 404 (silent — no log) |
| `Exception` | 500 |

---

## 2. Services

| Service | Caching | Notes |
|---------|---------|-------|
| `WeatherService` | `@Cacheable("pollen", key="#city")` | Ambee API → `PollenReport` |
| `GameService` | `@Cacheable("game-details", key="#gameId", unless="#result==null")` | Yahoo API → `Game` |
| `SportService` | delegates to `GameService` | sequential or parallel via `FanOut` |
| `DoryService` | none | transactional JPA CRUD |
| `EchoService` | none | diagnostic echo |
| `TraceService` | none | reads Micrometer `Tracer` context |

---

## 3. Outbound HTTP Clients

All clients use Spring `@HttpExchange` interfaces backed by `RestClient` +
JDK `HttpClient` (`HTTP_2` with ALPN negotiation — upgrades to HTTP/2 if
server supports it, falls back to HTTP/1.1 automatically).

All client beans are wired in `ClientConfig`:

### AmbeePollenClient

- Base URL: `https://ambee-maps-backend.ambeedata.com`
- Auth: none — browser-impersonating headers (Referer, Origin, sec-ch-ua)
- Methods: `getLatestPollen(lat, lng)`
- Logging: `LoggingInterceptor.noRedaction()`

### YahooSportsClient

- Base URL: `https://mrest.sports.yahoo.com/api/v8`
- Auth: none — browser User-Agent
- Methods: `getTeamGames(teamId, nextX, lastX)`, `getGameDetails(gameId)`
- 404 handling: translated to `null` before resilience layer via
  `wrapWithErrorHandling`. 404s are not retried and do not count as
  circuit breaker failures.

### GooglePollenClient

- Base URL: `https://pollen.googleapis.com`
- Auth: API key injected per-request via `QueryParamInterceptor`
- Methods: `getForecast(lat, lng, days)`
- Key redacted in logs: `LoggingInterceptor.withRedactedParams(Set.of("key"))`
- Secret loading: local JSON file on `dev`; GCP Secret Manager otherwise

---

## 4. Resilience — Circuit Breaker + Retry

Resilience4j applied via JDK dynamic proxy in `ClientConfig` — no AOP,
no annotations. Every method on every client interface gets transparent
circuit breaker + retry wrapping.

### Proxy chain (outermost → innermost)

```
circuit breaker proxy
  └── retry proxy
        └── [error-handling proxy]   ← Yahoo only (404 → null)
              └── Spring @HttpExchange proxy
                    └── RestClient → network
```

### Naming convention

| Resource | Key pattern | Example |
|----------|------------|---------|
| Circuit breaker | `<client>-<methodName>` | `yahoosports-getTeamGames` |
| Retry | `<client>` | `yahoosports` |

Per-endpoint circuit breakers mean one endpoint failing does not trip the
breaker for other endpoints on the same client.

Retry config is shared per-client (config only — retry state is per-call,
so methods are independent at runtime).

### Decorator order rationale

Retry is innermost, circuit breaker outermost. The breaker sees one outcome
per retry group — individual retry attempts are invisible to it. Only when
all retries are exhausted does the breaker record a failure. This prevents
transient blips from opening the circuit prematurely.

### Configuration (`application.properties`)

```properties
# Circuit breaker — per endpoint
resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.minimum-number-of-calls=5
resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.sliding-window-size=10
resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.wait-duration-in-open-state=30s

# Retry — per client (exponential backoff + jitter)
resilience4j.retry.instances.yahoosports.max-attempts=3
resilience4j.retry.instances.yahoosports.wait-duration=500ms
resilience4j.retry.instances.yahoosports.enable-exponential-backoff=true
resilience4j.retry.instances.yahoosports.exponential-backoff-multiplier=2
resilience4j.retry.instances.yahoosports.randomized-wait-factor=0.5
```

### Grafana / Prometheus queries

**Success rate per circuit breaker (5m window)**
```promql
rate(resilience4j_circuitbreaker_calls_seconds_count{kind="successful"}[5m])
/
rate(resilience4j_circuitbreaker_calls_seconds_count{kind=~"successful|failed"}[5m])
```

**Failure rate per circuit breaker**
```promql
rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[5m])
/
rate(resilience4j_circuitbreaker_calls_seconds_count{kind=~"successful|failed"}[5m])
```

**Circuit breaker state** (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
```promql
resilience4j_circuitbreaker_state
```

**Calls blocked by an open circuit**
```promql
rate(resilience4j_circuitbreaker_calls_seconds_count{kind="not_permitted"}[5m])
```

**Requests that succeeded only after at least one retry**
```promql
rate(resilience4j_retry_calls_total{kind="successful_with_retry"}[5m])
```

**Requests that exhausted all retries and still failed**
```promql
rate(resilience4j_retry_calls_total{kind="failed_with_retry"}[5m])
```

**p99 latency per endpoint (includes retry overhead)**
```promql
histogram_quantile(0.99,
  rate(resilience4j_circuitbreaker_calls_seconds_bucket[5m])
) by (name)
```

---

## 5. Cache

A three-tier cache abstraction built on Spring Cache, Caffeine (L1), and
Redis (L2).

### Tiers

| Type | Backend | Use case |
|------|---------|---------|
| `LOCAL` | Caffeine | pure in-process, no Redis dependency |
| `DISTRIBUTED` | Redis only | shared across replicas |
| `MULTI_LEVEL` | Caffeine + Redis | L1 hit avoids Redis round-trip |

### Key classes

- **`CacheDefinitionRegistry`** — single source of truth for all named
  caches (name, type, TTL, maxSize, dynamic TTL flag).
- **`RoutingCacheManager`** — primary Spring `CacheManager`; routes each
  cache to the right backend delegate.
- **`InstrumentedCache`** — outermost decorator; adds Micrometer Observation
  spans (`cache.get` with `cache.hit=true/false`, `cache.put`) visible in
  Jaeger, plus DEBUG hit/miss logging with remaining TTL (Caffeine only —
  Redis TTL check omitted to avoid extra round-trip per hit).
- **`Expirable`** interface — cached value objects implement
  `Duration cacheDuration()` for per-entry dynamic TTL.
- **`RedisExpirableCache`** — overrides `put()`: writes directly via
  `RedisTemplate` with the dynamic TTL using key format `"cacheName::key"`
  to match Spring's get path.
- **`MultiLevelCache`** — `get()`: check Caffeine → miss → check Redis →
  hit → back-fill Caffeine. Writes go to both levels.
- **`CacheConfigForHomeProfile`** (`@Profile("home")`) — routes all types
  to Caffeine; no Redis dependency.

### Registered caches

| Cache | Type | TTL | Notes |
|-------|------|-----|-------|
| `pollen` | `DISTRIBUTED` | 10 min | Redis only, shared across pods |
| `game-details` | `MULTI_LEVEL dynamic` | 10s (STARTED) / 60s (other) | `Game` implements `Expirable`; live games refresh more frequently |

### Grafana / Prometheus queries

**Cache hit rate**
```promql
rate(cache_gets_total{result="hit"}[5m])
/
rate(cache_gets_total[5m])
```

**Hit vs miss rate by cache name**
```promql
sum by (cache, result) (
  rate(cache_gets_total[5m])
)
```

**Cache put rate**
```promql
rate(cache_puts_total[5m])
```

**Cache eviction rate**
```promql
rate(cache_evictions_total[5m])
```

**Observation span latency for cache ops (p99)**
```promql
histogram_quantile(0.99,
  rate(spring_cache_get_seconds_bucket[5m])
) by (cache)
```

---

## 6. Concurrent Outbound Calls — FanOut

`FanOut` (`tools/FanOut.java`) is a virtual-thread parallel map utility.

```java
fanOut.map(games, g -> gameService.getGameDetails(g.gameId()),
           "get-game-details", g -> g.gameId())
```

### Design

- Uses `Executors.newVirtualThreadPerTaskExecutor()` — one virtual thread
  per item, no thread pool contention.
- Wrapped with Micrometer `ContextExecutorService` to propagate the parent
  thread's trace context (trace ID, MDC) into each virtual thread.
- Each task is wrapped in an `Observation` span named
  `virtual-thread:<spanName>` with the item ID as a low-cardinality key —
  visible as child spans in Jaeger.
- Null results are filtered out; per-item exceptions are logged as warnings
  and skipped (partial success).
- Executor opened in try-with-resources for structured lifecycle: shuts down
  after all futures complete.

`SportController` exposes a `?parallel=true` query param that switches
`SportService` from sequential `getTeamGames` to `getTeamGamesParallel`.

---

## 7. Dory Reminders — Persistence

H2 file-based embedded database via JPA/Hibernate.

### Schema (`ReminderEntity`)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `owner` | varchar(100) | |
| `title` | varchar(255) | |
| `type` | varchar(20) | `"one-off"` or `"recurring"` |
| `recurrence_type` | enum string | DAILY/WEEKLY/MONTHLY/YEARLY |
| `next_occurrence` | Instant | |
| `snooze_until` | Instant | nullable |
| `description` | varchar(1000) | |
| `status` | enum | LIVE / DONE |
| `completed_at` | Instant | nullable |

Index on `(owner, status)`.

`ddl-auto=update` — schema evolves automatically on deploy.

### Business logic

- **`markDone`**: one-off → `status=DONE, completedAt=now`. Recurring →
  advance `nextOccurrence` by recurrence interval, clear `snoozeUntil`.
- **`snooze`**: adds minutes to `snoozeUntil` (or `nextOccurrence` if not
  yet snoozed).

### Dory SPA

Static single-page app served from `static/dory/index.html`. Owner is read
from the URL path `/dory/{owner}`. Features: auto-polls every 30s, inline
contenteditable editing with on-blur save, inline date picker, delete
with confirm, urgency styling (overdue/snoozed), recurring badge.
`DoryUiController` forwards all `GET /dory/{owner}` requests to the SPA;
the SPA handles client-side routing.

---

## 8. Structured Logging

### Profile split (`logback-spring.xml`)

| Profile | Format | Root level |
|---------|--------|-----------|
| `dev` | Plain text: `time level [thread] [traceId spanId] logger - msg` | DEBUG |
| `home` | Same as dev | INFO |
| `kind`, `stage`, `prod` | JSON via `LogstashEncoder` | INFO |

### JSON log fields (kind/stage/prod)

`LogstashEncoder` emits all MDC fields as top-level JSON keys. Every log
line includes:

| Field | Source |
|-------|--------|
| `service` | hardcoded `"playground"` custom field |
| `traceId` | Micrometer tracing MDC bridge |
| `spanId` | Micrometer tracing MDC bridge |
| `http.method` | `AccessLogFilter` MDC |
| `http.uri` | `AccessLogFilter` MDC |
| `http.status` | `AccessLogFilter` MDC |
| `http.duration_ms` | `AccessLogFilter` MDC |
| `locale` | `LocaleFilter` MDC — resolved BCP 47 tag (e.g. `zh-TW`) |

`AccessLogFilter` (`@Order(1)`) also logs one line per request in Tomcat
Combined Log Format for human-readable access logs in pod stdout.

### Loki queries

**All logs for a pod**
```logql
{namespace="playground-dev", app="playground"}
```

**Errors only**
```logql
{namespace="playground-dev"} | json | level = "ERROR"
```

**Logs for a specific trace ID** (cross-service correlation)
```logql
{namespace="playground-dev"} | json | traceId = "abc123def456"
```

**Slow requests (> 500ms)**
```logql
{namespace="playground-dev"}
  | json
  | http_duration_ms > 500
  | line_format "{{.http_method}} {{.http_uri}} {{.http_status}} {{.http_duration_ms}}ms"
```

**All 5xx errors with URI**
```logql
{namespace="playground-dev"}
  | json
  | http_status >= 500
  | line_format "{{.http_status}} {{.http_method}} {{.http_uri}}"
```

**Request rate by endpoint (Loki metric query)**
```logql
sum by (http_uri) (
  rate({namespace="playground-dev"} | json | http_uri != "" [1m])
)
```

**Error rate over time**
```logql
sum(rate({namespace="playground-dev"} | json | level = "ERROR" [5m]))
```

**Access log pattern match** (Combined Log Format line)
```logql
{namespace="playground-dev"} |~ "GET /api/v1/sport"
```

**Logs from a specific pod**
```logql
{namespace="playground-dev", pod="service-playground-abc123"}
  | json
```

---

## 9. Distributed Tracing

Micrometer OpenTelemetry bridge exports traces to Jaeger via OTLP HTTP.

### Configuration

```properties
management.tracing.sampling.probability=1.0   # dev/kind: 100%
management.opentelemetry.tracing.export.enabled=true
# endpoint injected via env var MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
```

Empty-string default is intentionally NOT set in properties — Spring would
reject it as an invalid endpoint at startup. The env var is only set when
tracing is enabled (Helm injects it conditionally).

### What is traced

- Every inbound HTTP request (Spring MVC auto-instrumentation)
- Every outbound `RestClient` call (Micrometer observation)
- Every cache operation via `InstrumentedCache` (`cache.get`, `cache.put`)
- Every `FanOut` virtual thread task (`virtual-thread:<name>` child spans)

### Actuator paths excluded

`ActuatorObservationFilter` suppresses `/actuator/**` at the
`ObservationPredicate` level — no span is created at all (vs sampler-based
suppression which creates then discards the span).

### Jaeger UI queries

Search by service `playground` and filter by:

- Operation name: `GET /api/v1/sport/team/{teamId}/games`
- Tags: `http.status_code=503`
- Min duration: `1s` (find slow requests)
- Tags: `error=true`

---

## 10. Redis Connection Monitor

`RedisConnectionMonitor` (`monitor/`) — self-healing Redis connection
recovery.

- `@Profile("!home")` — excluded where Redis is not present.
- Pings Redis every 30s via `RedisTemplate.execute(conn -> conn.ping())`.
- Accumulates consecutive failures in a field (single scheduled-task
  instance shares state across calls — Spring creates one instance).
- After 3 consecutive failures: calls
  `LettuceConnectionFactory.resetConnection()` to discard the stuck
  connection. Lettuce will open a fresh connection on the next cache access.
- Logs recovery when ping succeeds after a failure run.

Rationale: Lettuce handles transient network blips with its own reconnect
logic. `resetConnection` is only needed when the connection is in a bad
state that Lettuce's reconnect cannot recover from on its own.

---

## 11. Observability Stack

All components run in the `observability` namespace in the KinD cluster.
Each is a standalone Helm chart under `infra/helm/`.

### Prometheus

Scrapes `/actuator/prometheus` on all pods annotated with
`prometheus.io/scrape=true`. Playground pods are auto-annotated by the
Helm deployment template.

- Scrape interval: 15s
- Retention: 7 days
- NodePort: `30090` → `http://localhost:30090`

**Useful queries**

```promql
# JVM heap usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# HTTP request rate by endpoint and status
sum by (uri, status) (
  rate(http_server_requests_seconds_count[5m])
)

# p99 HTTP response latency
histogram_quantile(0.99,
  sum by (uri, le) (
    rate(http_server_requests_seconds_bucket[5m])
  )
)

# HTTP error rate (4xx + 5xx)
sum(rate(http_server_requests_seconds_count{status=~"4..|5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))

# Active Tomcat threads
tomcat_threads_current_threads

# JVM GC pause time
rate(jvm_gc_pause_seconds_sum[5m])

# Caffeine cache hit ratio
cache_gets_total{result="hit", cache="game-details"}
  / cache_gets_total{cache="game-details"}

# Open file descriptors
process_open_fds

# Uptime
process_uptime_seconds
```

### Grafana

Pre-provisioned datasources: Prometheus (default), Loki, Jaeger.
Access: `http://localhost:30030` (admin/admin).

**Creating a dashboard for playground:**

1. New dashboard → Add panel
2. Select Prometheus datasource
3. Use any Prometheus query above
4. For logs panel: select Loki datasource, use LogQL queries from §8
5. For traces: select Jaeger datasource, search by service `playground`

**Correlate logs and traces:**

In a Loki panel showing a log line with `traceId`, click the trace ID to
jump directly to Jaeger — this works because Jaeger is provisioned as a
datasource with the `derivedFields` link configured.

### Loki

Receives logs from Promtail. Not directly exposed — query via Grafana.
Port 3100 (in-cluster only).

See §8 for LogQL query examples.

### Promtail (DaemonSet)

Tails `/var/log/pods/**/*.log` on each node. CRI pipeline stage handles
containerd log format. Attaches labels: `namespace`, `pod`, `container`,
`app`. Sends to `http://loki.observability:3100/loki/api/v1/push`.

Labels available in every Loki query:

```logql
{namespace="playground-dev", container="playground"}
{app="playground", pod=~"service-playground-.*"}
```

### Jaeger

- UI: `http://localhost:30686`
- OTLP HTTP ingestion: `http://localhost:30318` (NodePort from KinD)
- In-cluster: `http://jaeger.observability:4318/v1/traces`

**Useful trace searches in Jaeger UI:**

```
Service: playground
Operation: GET /api/v1/sport/team/{teamId}/games
Tags: http.status_code=503     → find upstream failures
Tags: error=true               → find errored spans
Min Duration: 500ms            → find slow requests
```

---

## 12. Infra — KinD Cluster

Config: `infra/kind/cluster-config.yaml`

1 control-plane + 2 workers. `extraPortMappings` expose all services to
`localhost`:

| Service | NodePort | Local URL |
|---------|---------|-----------|
| Playground | 30001 | `http://localhost:30001` |
| Redis | 30379 | `redis-cli -p 30379` |
| Jaeger UI | 30686 | `http://localhost:30686` |
| Jaeger OTLP | 30318 | used by local dev jar |
| Grafana | 30030 | `http://localhost:30030` |
| Prometheus | 30090 | `http://localhost:30090` |

### Deploy to KinD

```bash
./scripts/deploy-service-playground-kind.sh <git-sha>
```

Helm installs/upgrades the `service-playground` release in
`playground-dev` namespace using `values-kind.yaml`.

---

## 13. Infra — Helm Chart

Chart: `infra/helm/playground/`

### Values hierarchy

Helm merges `values.yaml` (defaults) with the environment-specific file.
All environments override `springProfile` to prevent the default `dev`
profile from loading `application-dev.properties` (which sets
`server.port=2000`):

| File | `springProfile` | Replicas | Redis | Tracing |
|------|----------------|----------|-------|---------|
| `values.yaml` (default) | `dev` | 1 | localhost | off |
| `values-kind.yaml` | `kind` | 2 | `redis.redis:6379` | 100%, Jaeger |
| `values-stage.yaml` | `stage` | 1 | GCP ClusterIP | — |
| `values-prod.yaml` | `prod` | 2 | GCP ClusterIP | — |

### Env vars injected by Helm

| Env var | Source | Purpose |
|---------|--------|---------|
| `SPRING_PROFILES_ACTIVE` | `springProfile` | Active Spring profile |
| `REDIS_HOST` / `REDIS_PORT` | `redisHost` / `redisPort` | Redis connection |
| `GCP_PROJECT_ID` | `gcpProjectId` | Secret Manager access |
| `OTEL_TRACING_ENABLED` | set if `otlpEndpoint` present | Enables OTLP export |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | `otlpEndpoint` | Jaeger endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `tracingSamplingProbability` | Sampling rate |
| `H2_DB_URL` | `h2DbUrl` | H2 file path |

### H2 persistence in KinD

`values-kind.yaml` sets `h2DataMount: true` and `h2HostPath:
~/.backyard`. The deployment template mounts this as a `hostPath` volume
at `/data` in the container, so `H2_DB_URL=jdbc:h2:file:/data/h2-kind`
persists the H2 file across pod restarts.

---

## 14. Infra — Argo CD (GitOps)

Manifests: `infra/argocd/applications/`

| App | Namespace | Sync | Values |
|-----|-----------|------|--------|
| `playground-stage` | `playground-stage` | Automatic (prune + selfHeal) | `values.yaml` + `values-stage.yaml` |
| `playground-prod` | `playground-prod` | Manual | `values.yaml` + `values-prod.yaml` |

**Promotion workflow:**

```bash
# Tag the image in both stage and prod values files
./scripts/set-playground-image-tag.sh <git-sha> both

# Commit and push → Argo CD auto-syncs stage;
# prod requires manual sync in Argo CD UI
git add infra/helm/playground/values-stage.yaml \
        infra/helm/playground/values-prod.yaml
git commit -m "chore(playground): promote <sha>"
git push
```

---

## 15. Deployment Profiles

| Profile | Port | Redis | Tracing | Managed by |
|---------|------|-------|---------|------------|
| `dev` | 2000 | localhost:30379 (KinD NodePort) | 100%, Jaeger NodePort | `mvnw spring-boot:run` |
| `home` | 3100 | none | off | pm2 |
| `kind` | 8080 | redis.redis:6379 (in-cluster) | 100%, Jaeger in-cluster | Helm / KinD |
| `stage` | 8080 | GCP env vars | off (configurable) | Argo CD (auto) |
| `prod` | 8080 | GCP env vars | off (configurable) | Argo CD (manual) |

`home` profile uses `CacheConfigForHomeProfile` — same cache abstraction
but all tiers backed by Caffeine (no Redis). Excluded from Redis monitor.
`spring.autoconfigure.exclude` removes all Redis auto-configuration.

---

## 16. Secrets Management

Google Maps API key is loaded by `QueryParamInterceptor.onlyApiKey()`:

- **`dev` profile** — reads `../../secrets/api_keys.json` (local file,
  not committed).
- **All other profiles** — reads from GCP Secret Manager using
  `google-cloud-secretmanager`. Secret name: `google_maps_api_key`.
  Project ID from `GCP_PROJECT_ID` env var.

The key is appended as a query param (`?key=...`) to every Google Pollen
API request. `LoggingInterceptor.withRedactedParams(Set.of("key"))` replaces
the value with `***` in logs.

---

## 17. Locale Pipeline (i18n)

End-to-end flow from inbound `Accept-Language` header to translated
response strings and forwarded outbound headers.

### Flow overview

```
Request
  Accept-Language: zh-TW,en;q=0.9
       │
       ▼
  LocaleFilter  (@Order(0))
  ├── Locale.LanguageRange.parse(header)
  ├── Locale.lookup(ranges, CANDIDATES) → Locale("zh-TW")
  ├── SupportedLocale.fromLanguageTag("zh-TW") → ZH_TW
  ├── extractRegion() → null  (no country subtag on zh-TW)
  ├── ClientLocaleContextHolder.set(ctx)
  ├── MDC.put("locale", "zh-TW")
  └── res.setHeader("Content-Language", "zh-TW")
            │
            ▼
  Controller / Service
  └── ClientLocaleContextHolder.get()  → ClientLocaleContext
            │
            ▼
  LocaleInterceptor  (outbound RestClient)
  └── mapper.apply(ctx.supportedLocale())
      → Accept-Language: zh-TW  (or mapped tag e.g. en-US for Yahoo)
```

### `SupportedLocale` enum

Single source of truth for all locales the service handles.

| Constant | BCP 47 tag | Notes |
|----------|-----------|-------|
| `EN` | `en` | default / fallback |
| `FR` | `fr` | |
| `ES` | `es` | |
| `ZH_TW` | `zh-TW` | Traditional Chinese |
| `ZH_CN` | `zh-CN` | Simplified Chinese |
| `JA` | `ja` | |
| `DE` | `de` | |
| `PT_BR` | `pt-BR` | Brazilian Portuguese |

Resolution uses two-step lookup:
1. Exact match (`zh-TW` → `ZH_TW`)
2. Language-prefix fallback (`en-US` → `en` → `EN`)

Only add a new constant when you have a separate translation file for it.

### `LocaleFilter`

Runs at `@Order(0)` — before `AccessLogFilter` (`@Order(1)`) so the
`locale` MDC key is present in every log line for the request.

**Resolution algorithm:**
1. Parse `Accept-Language` with RFC 5646 `Locale.LanguageRange.parse`.
2. `Locale.lookup(ranges, CANDIDATES)` against pre-built candidate list
   (one `Locale` per `SupportedLocale` constant).
3. No match or absent header → fall back to `SupportedLocale.EN`.

**Region extraction:** scans ranges in priority order for the first range
whose language matches the resolved locale; returns its country subtag
(e.g. `"CA"` from `en-CA`). `null` when no range carries a region.
Extracted from the raw header, not the resolved `SupportedLocale` — the
supported entries use bare language tags so `Locale.lookup` would strip
any region during matching.

**Response headers set by the filter:**
- `Vary: Accept-Language` — tells caches the response varies by locale.
- `Content-Language: <tag>` — tells the client the response language.

**Thread propagation:** `ClientLocaleContextHolder` uses
`InheritableThreadLocal`. Virtual threads spawned by `FanOut` inherit the
context at creation time from the Tomcat request thread — no extra wiring.
`LocaleFilter` always calls `ClientLocaleContextHolder.clear()` in a
`finally` block to prevent leaks between requests.

### `ClientLocaleContext`

Immutable record, one per request.

| Field | Type | Description |
|-------|------|-------------|
| `raw` | `String` | Raw `Accept-Language` header; `null` if absent |
| `supportedLocale` | `SupportedLocale` | Resolved locale; never `null` |
| `region` | `String` | ISO 3166-1 alpha-2 country code; `null` if none |

`supportedLanguageTag()` is a convenience shorthand for
`supportedLocale().getLanguageTag()`.

### `LocaleInterceptor`

Adds `Accept-Language` to every outbound `RestClient` request. Takes a
`Function<SupportedLocale, String> mapper` so each client can translate
to the tag its upstream API accepts:

```java
// Yahoo Sports — only accepts en-US / es-US
new LocaleInterceptor(YahooSportsLocale::fromServiceLocale)

// Client that accepts standard BCP 47 tags directly
new LocaleInterceptor(SupportedLocale::getLanguageTag)
```

### MessageSource / translation files

`spring.messages.basename=i18n/messages` (set in `application.properties`).
Spring Boot defaults to UTF-8 encoding — characters can be written
directly (no `\uXXXX` escapes needed).

Files live under `src/main/resources/i18n/`:

| File | Locale |
|------|--------|
| `messages.properties` | English (source of truth) |
| `messages_fr.properties` | French |
| `messages_es.properties` | Spanish |
| `messages_de.properties` | German |
| `messages_ja.properties` | Japanese |
| `messages_zh_CN.properties` | Simplified Chinese |
| `messages_zh_TW.properties` | Traditional Chinese |
| `messages_pt_BR.properties` | Brazilian Portuguese |

### `echo/hello` demo endpoint

`GET /api/v1/echo/hello` demonstrates the full pipeline end-to-end:

```java
var ctx   = ClientLocaleContextHolder.get();
var label = messageSource.getMessage("hello", null,
                ctx.supportedLocale().toLocale());
return ok(new HelloResult(ctx.supportedLanguageTag(), label));
```

Example responses:

```bash
curl -H "Accept-Language: zh-TW" localhost:2000/api/v1/echo/hello
# {"locale":"zh-TW","hello":"你好"}

curl -H "Accept-Language: ja" localhost:2000/api/v1/echo/hello
# {"locale":"ja","hello":"こんにちは"}

curl localhost:2000/api/v1/echo/hello
# {"locale":"en","hello":"Hello"}
```

---

## 18. API Documentation

OpenAPI 3 spec generated at startup by springdoc-openapi. Swagger UI
available at `/swagger-ui.html` for interactive browsing and testing.

### URLs

| Path | Description |
|------|-------------|
| `/swagger-ui.html` | Interactive Swagger UI |
| `/v3/api-docs` | OpenAPI JSON spec |

### Versioning limitation

springdoc does not understand Spring Framework 7's `version = "1+"` attribute
on `@GetMapping` etc. It treats `/{version}` as a literal path parameter.

**Workaround:** `OpenApiConfig` sets the server base URL to `/api/v1` so spec
paths are clean (`/echo`, `/dory/reminders`, ...). An `OpenApiCustomizer` bean
(`stripVersionPrefix`) strips the `/api/<version>` prefix from every generated
path before the spec is served. Swagger UI then constructs full URLs by
combining `/api/v1` + `/echo` → `localhost:2000/api/v1/echo`.

**If v2 endpoints are added:** create a `@GroupedOpenApi` bean per version,
each filtering by path prefix (`/api/v1/**`, `/api/v2/**`), and set per-group
server URLs. Each group gets its own spec at `/v3/api-docs/{group}`.

### `WebMvcConfig` exclusion

The `/api` path prefix is applied to all `@RestController` beans except
`org.springdoc.*` — without this, springdoc's own controllers receive the
prefix, turning `/v3/api-docs` into `/api/v3/api-docs`, which the versioning
middleware rejects as an invalid API version.

### Per-controller annotations

Every public controller is annotated with:
- `@Tag(name, description)` — groups endpoints in Swagger UI
- `@Operation(summary)` — one-line description per endpoint
- `@ApiResponse` with `@Schema(implementation = XyzResponse.class)` — explicit
  response schema required because return types are `ResponseEntity<Object>`

`ErrorResponse` (from `GlobalExceptionHandler`) is referenced as the schema
for all 4xx/5xx responses.

### Prod profile

Swagger UI and the spec endpoint are disabled in production:

```properties
# application-prod.properties
springdoc.swagger-ui.enabled=false
springdoc.api-docs.enabled=false
```
