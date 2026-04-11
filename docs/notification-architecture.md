# Notification System Architecture

## Overview

A topic-based push notification system supporting 100–1000 topics with up
to 2M subscribers each, designed to scale to billions of users. Mobile
users (iOS/Android) can have multiple devices. The system covers three
concerns:

- **Write path** — device registration, subscribe/unsubscribe (in
  `playground`)
- **Fan-out path** — read subscribed push tokens and dispatch send jobs
  (`notification-consumer`)
- **Send path** — APNS/FCM delivery (`notification-consumer`, dry-run
  logging for now)

## Services

```
services/
├── pom.xml                  aggregator — parent for all services
├── notification-common/     shared Cassandra entities + repositories
│                            (used by playground and notification-consumer)
├── playground/              existing — extended with write API +
│                            event detector + Kafka producer
└── notification-consumer/   new — all Kafka consumers (fan-out,
                             device resolver, sender, retry)
```

Cassandra schema: `infra/cassandra/schema.cql`

### Build commands

```bash
# Build all modules in dependency order (from repo root)
./mvnw clean package -f services/pom.xml

# Build playground only (also builds notification-common first)
./mvnw clean package -f services/pom.xml -pl playground -am

# Build notification-consumer only
./mvnw clean package -f services/pom.xml -pl notification-consumer -am

# Build notification-common only
./mvnw clean package -f services/pom.xml -pl notification-common
```

---

## System Architecture

```
                      ┌─────────────────────────────────────┐
                      │            playground               │
                      │                                     │
[Mobile Client] ─────▶│  POST   /api/v1/notifications/      │──▶ Cassandra
                      │         devices                     │    push_destinations_by_user
                      │  DELETE /api/v1/notifications/      │
                      │         devices/{deviceId}          │
                      │  POST   /api/v1/notifications/      │──▶ Cassandra
                      │         subscriptions               │    subscriptions
                      │  DELETE /api/v1/notifications/      │
                      │         subscriptions               │
                      │  GET    /api/v1/notifications/      │──▶ MySQL
                      │         topics                      │    topics
                      │                                     │
[Event source] ──────▶│  @Scheduled EventDetector           │
                      │    publishes N=100 fanout-jobs      │
                      │    messages per triggered event     │
                      └────────────────┬────────────────────┘
                                       │
                              [fanout-jobs (Kafka)]
                              100 msgs/event, 16 partitions
                                       │
                      ┌────────────────▼────────────────────┐
                      │        notification-consumer        │
                      │                                     │
                      │  FanOutConsumer                     │
                      │  @KafkaListener("fanout-jobs")      │
                      │    reads Cassandra subscriptions    │
                      │    by (topic_id, token_range)       │
                      │    pages 1000 user_ids at a time    │
                      │                │                    │
                      │       [user-batch-queue (Kafka)]    │
                      │                │                    │
                      │  PushDestinationResolverConsumer             │
                      │  @KafkaListener("user-batch-queue") │
                      │    1000 concurrent async reads      │
                      │    → push_destinations_by_user                │
                      │    filter: session_active           │
                      │          + notifications_enabled    │
                      │          + token_valid              │
                      │    split by platform + batch size   │
                      │                │                    │
                      │       [notification-send (Kafka)]   │
                      │                │                    │
                      │  SendConsumer                       │
                      │  @KafkaListener("notification-send")│
                      │    ├── ApnsSender (dry-run: log)    │
                      │    └── FcmSender  (dry-run: log)    │
                      │          │                          │
                      │          ├── token invalid          │
                      │          │   → mark token_valid=f   │
                      │          │     in Cassandra         │
                      │          ├── transient error        │
                      │          │   → notification-retry   │
                      │          └── permanent error        │
                      │              → notification-dlq     │
                      │                                     │
                      │  RetryConsumer                      │
                      │  @KafkaListener("notification-retry"│
                      │    exponential backoff (attempt×5s) │
                      │    attempt < 3 → notification-send  │
                      │    attempt ≥ 3 → notification-dlq   │
                      └─────────────────────────────────────┘
```

---

## Data Stores

### MySQL (in `playground`, existing)

No new MySQL tables for notifications. Topics are plain strings — no
pre-registration required. A subscription is `(user_id, "sports")` in
Cassandra. The existing `users` / `refresh_tokens` tables are unchanged.

### Cassandra (new, 3-node cluster, RF=3)

Two tables using `user_id` (UUID) as the partition key directly. UUID
partition keys produce billions of tiny (~2KB) partitions — the
Cassandra-idiomatic approach. Cassandra constrains partition SIZE (keep
under 100MB); partition COUNT has no upper limit. Manual bucketing
(`user_id mod X`) is not used because it causes partitions to grow
unboundedly as users scale.

#### `push_destinations_by_user`

One partition per user, one row per push token. The push token is the
unique identifier — no separate device ID needed. APNS and FCM both
guarantee a token uniquely identifies one app installation. State changes
(login/logout, notification toggle, token invalidation) are single-row
updates within a single partition. Token rotation requires a delete of
the old row and an insert of the new row (push_token is immutable as a
Cassandra primary key column).

```cql
CREATE TABLE push_destinations_by_user (
  user_id               UUID,
  push_token            TEXT,    -- clustering key; APNS ≤256 chars, FCM ≤4096 chars
  platform              TEXT,    -- 'APNS' or 'FCM'
  bundle_id             TEXT,    -- APNS only: target app bundle ID
  apns_env              TEXT,    -- APNS only: 'SANDBOX' or 'PRODUCTION'
  locale                TEXT,
  session_active        BOOLEAN,
  notifications_enabled BOOLEAN,
  token_valid           BOOLEAN,
  registered_at         TIMESTAMP,
  updated_at            TIMESTAMP,
  PRIMARY KEY (user_id, push_token)
);
```

#### `subscriptions`

One partition per user, one row per topic subscription. Rows are
**hard-deleted** on unsubscribe or expiry — presence of a row means
the subscription is active. No `active` flag. Cassandra tombstones from
deletes are cleaned by compaction after `gc_grace_seconds` (10 days).

`topic_id` is the clustering key. The SAI index on `topic_id` enables
fan-out reads across all user partitions without a second table. User
lookup (`WHERE user_id = U`) hits one partition directly — no index needed.

```cql
CREATE TABLE subscriptions (
  user_id       UUID,
  topic_id      TEXT,
  subscribed_at TIMESTAMP,
  PRIMARY KEY (user_id, topic_id)
);

-- SAI (Storage-Attached Index) — requires Cassandra 4.0+
-- Per-SSTable index: efficient cross-partition reads by topic_id.
-- User lookup WHERE user_id=U hits a single partition — SAI not used.
-- Fan-out WHERE topic_id=T uses SAI across all partitions.
CREATE INDEX ON subscriptions (topic_id) USING 'sai';
```

**Access patterns:**

| Query | Path | Use case |
|---|---|---|
| `WHERE user_id = U` | Single partition, no index | User views own subscriptions |
| `WHERE user_id = U AND topic_id = T` | Single partition, clustering key | Check subscription status |
| `WHERE topic_id = T AND token(user_id) >= X AND < Y` | SAI + token range | Fan-out read for one range |

---

## Fan-Out Pipeline

### Why token ranges?

The Cassandra token space is a fixed int64 ring (`-2^63` to `+2^63`).
Users are distributed evenly across the ring by Murmur3 hash of their
`user_id`. The fan-out splits this ring into N=100 equal segments.
Each segment becomes one independent unit of work — small enough for
one consumer to finish quickly, many enough to average out subscriber
distribution imbalances.

```
N = 100  (configurable; change requires only a redeploy, no schema change)

Token range i:
  start = Long.MIN_VALUE + i * (2^64 / N)
  end   = start + (2^64 / N)   [last range ends at Long.MAX_VALUE]
```

### Stage A — FanOutConsumer

The event detector publishes N=100 messages to `fanout-jobs` when an
event fires — one message per token range. Each message is self-contained:

```json
{
  "topicId": "sports",
  "eventId": "uuid",
  "title": "...", "body": "...", "data": {},
  "rangeIndex": 3,
  "rangeStart": -2305843009213693952,
  "rangeEnd":   -614891469123651584
}
```

Messages are keyed by `rangeIndex % 16` to distribute evenly across 16
Kafka partitions. Each of the 8 consumer threads owns 2 Kafka partitions
and processes messages sequentially within them — ~12–13 token ranges
per consumer per event.

Cassandra query per message:

```cql
SELECT user_id FROM subscriptions
WHERE topic_id         = :topicId
  AND token(user_id) >= :rangeStart
  AND token(user_id) <  :rangeEnd;
```

Results are paged in batches of 1000 `user_id` values and published to
`user-batch-queue`.

### Stage B — PushDestinationResolverConsumer

For each batch of 1000 `user_id` values, fires 1000 concurrent async
reads to `push_destinations_by_user` using the Cassandra Java driver's non-blocking
API (Netty + stream IDs — all 1000 requests multiplex over one TCP
connection per node):

```java
List<CompletableFuture<AsyncResultSet>> futures = userIds.stream()
    .map(id -> session.executeAsync(selectPushDestinations.bind(id))
                      .toCompletableFuture())
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

Because Stage A user_ids all come from the same token range, their
`push_destinations_by_user` partitions are physically adjacent in SSTables on the
same 1–3 Cassandra nodes. OS read-ahead warms the page cache for the
whole batch automatically.

**`WHERE user_id IN (...)` is not used** — it serializes reads on the
coordinator and is slower than concurrent async reads.

Expected latency for 1000 concurrent reads:
- Hot (page cache hit): 5–15ms
- Warm: 15–40ms
- Cold: 20–80ms

After device reads, filters in-memory:
`session_active = true AND notifications_enabled = true AND token_valid = true`

Publishes to `notification-send`, split by platform and capped at:
- APNS: ≤200 tokens/message (no batch API; one HTTP/2 call per token)
- FCM: ≤500 tokens/message (FCM batch API limit)

### Stage C — SendConsumer

Dry-run mode (current):
- APNS: `log.info("APNS DRY-RUN | token={} bundle={} env={} title={}")`
- FCM:  `log.info("FCM  DRY-RUN | token={} title={}")`

Production (future — add pushy 0.15.4 and firebase-admin 9.4.2):
- APNS: pushy library, one HTTP/2 call per token
- FCM: Firebase Admin SDK, batch up to 500 tokens

Error handling (per token, not per batch):

| Response | Action |
|---|---|
| Success | Done |
| APNS 410 / FCM `UNREGISTERED` | `UPDATE push_destinations_by_user SET token_valid=false`; no retry |
| Transient (429, 503) | Publish to `notification-retry` with `attempt+1` |
| `attempt ≥ 3` | Publish to `notification-dlq` |
| Other permanent 4xx | Publish to `notification-dlq` |

### Stage D — RetryConsumer

Reads `notification-retry`. Sleeps `attempt × 5s` (exponential backoff),
then re-publishes to `notification-send` with `attempt+1`. At
`attempt ≥ 3`, publishes to `notification-dlq` instead.

---

## Kafka Topics

| Topic | Partitions | Key | Retention | Notes |
|---|---|---|---|---|
| `notification-events` | 16 | `topic_id` | 7d | One message per triggered event |
| `fanout-jobs` | 16 | `rangeIndex % 16` | 1d | 100 messages/event, ~6–7 per partition |
| `user-batch-queue` | 16 | `rangeIndex % 16` | 1d | 1000 user_ids per message, token-range co-located |
| `notification-send` | 16 | `push_token` | 3d | APNS ≤200 tokens, FCM ≤500 tokens per message |
| `notification-retry` | 8 | `push_token` | 3d | Per-token, includes `attempt` counter |
| `notification-dlq` | 4 | `push_token` | 30d | Manual inspection / replay |

---

## Configuration

```
Cassandra token ranges (N):       100  (redeploy to change, no schema change)
Cassandra cluster:                3 nodes, RF=3
Kafka partitions (major topics):  16
Consumer pods:                    2
Consumer threads per pod:         4   (concurrency=4 per @KafkaListener)
Total consumers per topic:        8   (2 pods × 4 threads)
Kafka partitions per consumer:    2   (16 / 8)
Messages per event:               100
Messages per Kafka partition:     6–7
Token ranges per consumer/event:  ~12–13
```

---

## Kubernetes Deployment (kind cluster)

```
kind cluster
├── playground             Deployment, 2 pods
│     write API + event detector + Kafka producer
├── notification-consumer  Deployment, 2 pods
│     all 4 @KafkaListener beans, 4 virtual threads each
│     = 8 concurrent consumers per topic across 2 pods
├── kafka                  StatefulSet, 3 pods (brokers)
├── cassandra              StatefulSet, 3 pods, RF=3
└── mysql                  StatefulSet, 1 pod
```

Worker pods require no Kubernetes `Service` — outbound connections only
(Kafka, Cassandra). Liveness/readiness via Spring Boot Actuator
(`/actuator/health/liveness`, `/actuator/health/readiness`).

**Cassandra pod resources:**
```yaml
resources:
  requests: { cpu: "2", memory: "4Gi" }
  limits:   { cpu: "4", memory: "4Gi" }
# JVM heap: 2GB; remaining ~2GB for OS page cache (SSTable reads)
storage: 20Gi PVC per pod

keyspace replication: SimpleStrategy, RF=3
read  consistency: LOCAL_ONE     (fastest, nearest replica)
write consistency: LOCAL_QUORUM  (2/3 nodes; survives 1 failure)
```

**Scaling note:** `notification-consumer` runs all consumer types in fixed
proportion (4 threads each per pod). If independent scaling is needed at
higher load — e.g. fan-out is the bottleneck but sender is idle — the
consumers can be split into separate services (`notification-fanout`,
`notification-sender`), each with its own K8s Deployment, with no
Kafka or Cassandra schema changes required.

---

## Virtual Threads

Spring Boot 4's `spring.threads.virtual.enabled=true` covers Tomcat,
`@Async`, and `@Scheduled` but does **not** automatically apply to Kafka
listener containers. `notification-consumer` wires virtual threads
explicitly via a `KafkaConfig` bean:

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object>
        kafkaListenerContainerFactory(
                ConsumerFactory<String, Object> consumerFactory) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    factory.setConsumerFactory(consumerFactory);
    var executor = new SimpleAsyncTaskExecutor("kafka-consumer-");
    executor.setVirtualThreads(true);
    factory.getContainerProperties().setListenerTaskExecutor(executor);
    return factory;
}
```

| Consumer | Primary work | Virtual thread benefit |
|---|---|---|
| `FanOutConsumer` | Async non-blocking Cassandra reads | Minimal |
| `PushDestinationResolverConsumer` | Async non-blocking Cassandra reads | Minimal |
| `SendConsumer` | Blocking HTTP/2 (APNS/FCM) in production | High |
| `RetryConsumer` | `Thread.sleep()` backoff | High |

---

## REST API (playground)

`@RequestMapping("/{version}/notifications")` —
`/api` prefix added automatically by `WebMvcConfig`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/push-destinations` | Register or update a push token for a user |
| `DELETE` | `/push-destinations/{pushToken}` | Deactivate a push destination (logout / uninstall / token rotate) |
| `POST` | `/subscriptions` | Subscribe a user to a topic |
| `DELETE` | `/subscriptions` | Unsubscribe a user from a topic |
| `GET` | `/subscriptions` | List a user's active subscriptions (SAI read) |

---

## APNS and FCM PushDestination Data Requirements

### APNS (via pushy library)

| Field | Stored in | Notes |
|---|---|---|
| `push_token` | `push_destinations_by_user.push_token` | Hex string, ≤256 chars |
| `bundle_id` | `push_destinations_by_user.bundle_id` | Required per push destination (topic in APNS terms) |
| `apns_env` | `push_destinations_by_user.apns_env` | `SANDBOX` or `PRODUCTION` |

Notification payload: `title`, `body`, `badge`, `sound`, `category`,
`interruption_level`, `relevance_score`, custom `data` map.

### FCM (via Firebase Admin SDK)

| Field | Stored in | Notes |
|---|---|---|
| `push_token` | `push_destinations_by_user.push_token` | Up to 4096 chars |
| `registered_at` | `push_destinations_by_user.registered_at` | Token freshness; Google recommends refreshing monthly |

Notification payload: `notification.title`, `notification.body`,
`data` map (string values only; avoid reserved keys `from`,
`message_type`, `gcm.*`, `google.*`).

Firebase project credentials handle app targeting — no `bundle_id`
equivalent needed per push destination.

---

## Verification Checklist

1. **Cassandra schema** — create tables + SAI index; verify with
   `EXPLAIN SELECT ... WHERE topic_id = 'sports'` that SAI is used.

2. **Push destination registration** — `POST /api/v1/notifications/push-destinations`;
   confirm row in `push_destinations_by_user`; duplicate push token upserts
   correctly.

3. **Subscribe** — `POST /api/v1/notifications/subscriptions`; confirm
   row in `subscriptions`; duplicate is idempotent.

4. **User lookup** — `GET /api/v1/notifications/subscriptions?userId=U`;
   confirm single-partition read, latency ≤ 150ms.

5. **Fan-out** — trigger event detector (or publish directly to
   `notification-events`); confirm 100 `fanout-jobs` messages published,
   consumed across 8 threads; confirm `notification-send` receives one
   message per push destination batch.

6. **Dry-run log** — confirm `ApnsSender` / `FcmSender` log lines show
   correct token, bundle ID, platform, title, body.

7. **Retry path** — inject transient error in `SendConsumer`; confirm
   message on `notification-retry` with `attempt=2`, then
   `notification-dlq` after 3 attempts.

8. **Token invalidation** — inject APNS 410 response; confirm
   `push_destinations_by_user` row updated `token_valid=false`, no retry entry.
