# Redis Cache Implementation Plan

## Stack Overview

```
Your code (@Cacheable)
  → Spring Cache Abstraction (RedisCacheManager)
    → Spring Data Redis (RedisTemplate / RedisCacheWriter)
      → Lettuce (Redis client, connection pooling via Netty)
        → Redis server
```

**Lettuce** is always the actual Redis client. `RedisCacheManager` bridges
Spring's generic `@Cacheable` abstraction to Redis. `RedisTemplate` wraps
Lettuce with Java-typed operations and connection management — named after the
Template Method design pattern (not string templating). `opsForValue()`,
`opsForHash()`, etc. are groupings of Redis commands by data structure.

---

## Step 1 — Redis Pod in Kind

- Create `infra/helm/redis/` — minimal chart, single-instance Redis
  - `Chart.yaml`
  - `templates/deployment.yaml` — `redis:8.6-alpine` image, no persistence,
    no auth (dev only)
  - `templates/service.yaml` — `ClusterIP`, port `6379`
  - `values.yaml`
- Image: `redis:8.6-alpine` (Redis 8.6 is current stable GA as of March 2026;
  alpine keeps image size small for Kind)
- Playground pod reaches Redis at `redis.redis:6379` (service.namespace)
- Add Redis deploy step to Kind deploy script or a separate
  `deploy-redis-kind.sh`

---

## Step 2 — Spring Boot Dependency & Connection

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

This pulls in Lettuce automatically. No need to add Lettuce separately.

`application.properties` defaults:
```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

Override in Kind/stage via Helm env vars:
```
SPRING_DATA_REDIS_HOST=redis.redis
SPRING_DATA_REDIS_PORT=6379
```

---

## Step 3 — Serialization: Jackson JSON

Spring's `RedisCacheManager` uses Java serialization by default. **Do not use
Java serialization** — it is:

- **Fragile** — renaming or adding a field breaks deserialization of existing
  Redis entries; requires Redis flush on every deploy touching a cached class
- **Unreadable** — binary bytes, cannot inspect with `redis-cli`
- **JVM-only** — no other language can read the data
- **Security risk** — Java deserialization gadget chain attacks allow arbitrary
  code execution. An attacker writes crafted bytes to a Redis key; when your
  app deserializes it, the JVM executes attacker code before your application
  code runs. The ysoserial tool makes this trivially easy against apps using
  Java serialization.

**Use Jackson JSON instead.** Stores plain readable JSON. Unknown fields are
ignored by default so schema changes don't break existing entries. Works for
any Java type including `Map<String, Integer>`, `List<T>`, and generic classes
(requires `TypeReference` for generics to work around type erasure).

Configure `RedisCacheConfiguration` with a `Jackson2JsonRedisSerializer` or
`GenericJackson2JsonRedisSerializer` using Spring Boot's auto-configured
`ObjectMapper`.

---

## Step 4 — TTL Strategy

### Fixed TTL (e.g. `pollen`)

Spring's `RedisCacheManager` supports fixed TTL per cache name natively via
`RedisCacheConfiguration.entryTtl(Duration)`. All entries in that cache share
the same TTL.

| Cache | Caffeine TTL | Redis TTL |
|---|---|---|
| `pollen` | 10 min | 1 hr |

Redis TTL is longer than Caffeine because Redis is a shared distributed cache —
evicting less frequently reduces load on the upstream API.

### Dynamic per-item TTL (e.g. `game-details`)

Spring's `RedisCacheManager` has no equivalent of Caffeine's `Expiry` interface.
By the time `RedisCacheWriter.put()` is called, the value is already serialized
to bytes — it can't inspect `Expirable` anymore.

**Solution:** a custom `Cache` wrapper overrides `put()`:

```
put(key, value):
  if value implements Expirable:
    duration = value.cacheDuration()
    → RedisTemplate.opsForValue().set(key, serialize(value), duration)
  else (or if cacheDuration() returns null):
    log warning → use configured default TTL
    → delegate to Spring's RedisCache
```

This mirrors the Caffeine `buildCacheWithDynamicTtl` pattern exactly — same
`Expirable` contract, same warn-and-fallback behavior on null duration.

**`CacheConfig` builder methods (parallel to Caffeine):**

```java
// Fixed TTL — delegates fully to Spring's RedisCache
buildRedisCache(name, ttl)

// Dynamic TTL — wraps Spring's RedisCache, overrides put() for Expirable
buildRedisCacheWithDynamicTtl(name, defaultTtl)
```

---

## Step 5 — Redis Cache Layer Implementation

Goal: a standalone Redis-only cache layer before adding multi-level support.
All classes live in `com.backyard.playground.cache`.

### New class: `RedisExpirableCache`

Wraps Spring's `RedisCache`. Overrides `put()` to support per-item TTL via
`Expirable`. All other operations (`get`, `evict`, `clear`) delegate directly
to the underlying `RedisCache`.

Key fields:
- `RedisCache delegate` — Spring's built-in Redis cache
- `RedisTemplate<String, Object> redisTemplate` — for custom TTL puts
- `Duration defaultTtl` — fallback when value doesn't implement `Expirable`
  or `cacheDuration()` returns null
- `RedisSerializer valueSerializer` — shared Jackson serializer for consistent
  serialization between custom puts and normal RedisCache gets

### Update `CacheConfig`

- Inject `RedisCacheManager` and `RedisTemplate` beans (auto-configured by
  Spring Boot once `spring-boot-starter-data-redis` is on the classpath)
- Add `buildRedisCache(name, ttl)` — builds from `RedisCacheManager`
- Add `buildRedisCacheWithDynamicTtl(name, defaultTtl)` — wraps result in
  `RedisExpirableCache`
- Wrap result with `InstrumentedCache` — `cacheType` will automatically be
  `"RedisExpirableCache"` or `"RedisCache"` via `getSimpleName()`

### `InstrumentedCache` TTL logging

`remainingSeconds()` currently handles Caffeine only. For Redis:
- Skip TTL lookup — querying Redis TTL requires a `TTL key` network call,
  too expensive just for a log line
- Return `OptionalLong.empty()` — existing code already handles this case,
  logs the hit without TTL detail
- The TODO placeholder is already in place

---

## Step 6 — Multi-level Cache (future)

Deferred until Redis-only layer is working and tested.

```
MultiLevelCache.get(key):
  L1 (Caffeine) hit  → return
  L1 miss → L2 (Redis) get
    L2 hit  → backfill L1 with full L1 TTL → return
    L2 miss → return null (Spring calls real method → put into both)

MultiLevelCache.put(key, value):
  write to L1 and L2

MultiLevelCache.evict(key):
  evict from both

MultiLevelCache.clear():
  clear both
```

**Open questions for when this is implemented:**
- Backfill TTL on L2 hit: use full L1 TTL (simple) or remaining Redis TTL
  (correct but requires extra `TTL key` network call)?
- `InstrumentedCache` wrapping: one wrapper around `MultiLevelCache` (one
  span per operation) or wrap each layer individually (separate L1/L2 spans
  for more visibility)?

---

## Key Design Decisions Summary

| Decision | Choice | Reason |
|---|---|---|
| Redis client | Lettuce (via Spring Boot auto-config) | Standard, async, connection pooling |
| Serialization | Jackson JSON | Readable, resilient, secure |
| Fixed TTL | `RedisCacheConfiguration.entryTtl()` | Native Spring support |
| Dynamic TTL | Custom `RedisExpirableCache` wrapper | Mirrors Caffeine `Expirable` pattern |
| Tracing | `InstrumentedCache` wraps Redis layer | Consistent with Caffeine instrumentation |
| Redis image | `redis:8.6-alpine` | Current stable GA, small image |
