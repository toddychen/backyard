package com.backyard.playground.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Decorator around Spring's {@link RedisCache} that adds per-entry dynamic TTL
 * support via the {@link Expirable} contract.
 *
 * <h3>Why a decorator, not a subclass</h3>
 *
 * <p>
 * Spring's {@code RedisCache} has private internals — the key serializer, cache
 * writer, and key prefix are not accessible to subclasses without reflection. A
 * decorator owns {@code put()} entirely: it calls {@link RedisTemplate}
 * directly for dynamic TTL entries and delegates to the wrapped
 * {@code RedisCache} for everything else.
 *
 * <h3>Why this works for Redis but not Caffeine</h3>
 *
 * <p>
 * Redis supports per-command TTL ({@code SET key value EX ttl}), so a decorator
 * can override {@code put()} and set any TTL at write time. Caffeine has no
 * equivalent — its TTL policy is baked into the cache at construction time via
 * {@code expireAfter(Expiry)}. See {@link CaffeineBackedCacheManager} for how
 * Caffeine handles dynamic TTL instead.
 *
 * <h3>Key format alignment</h3>
 *
 * <p>
 * The Redis key written by the custom {@code put()} must exactly match what
 * Spring's {@code
 * RedisCache.get()} expects. Spring's default key format is
 * {@code "cacheName::key"} — this class constructs that same prefix so that
 * custom puts are readable by normal gets.
 *
 * <p>
 * The value serializer passed to {@link RedisTemplate} must also match the
 * serializer configured in {@link RedisBackedCacheManager} — both use the same
 * {@code GenericJacksonJsonRedisSerializer} instance to ensure values written
 * here are deserializable by Spring's {@code RedisCache.get()}.
 */
public class RedisExpirableCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(RedisExpirableCache.class);

    // Spring's default key prefix format — must match RedisCache implementation
    private static final String KEY_PREFIX_SEPARATOR = "::";

    private final RedisCache delegate;
    private final RedisTemplate<String, Object> redisTemplate;

    // Default TTL is not stored here — it is baked into the delegate's
    // RedisCacheConfiguration at construction time in RedisBackedCacheManager.
    // When cacheDuration() returns null we fall through to delegate.put(),
    // which uses that configured TTL automatically.
    public RedisExpirableCache(RedisCache delegate, RedisTemplate<String, Object> redisTemplate) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Writes the entry to Redis using the TTL from
     * {@link Expirable#cacheDuration()}. If the value does not implement
     * {@link Expirable}, or {@code cacheDuration()} returns null, the entry is NOT
     * saved and a warning is logged. This cache requires all values to carry their
     * own TTL — there is no fixed fallback.
     */
    @Override
    public void put(Object key, Object value) {
        if (value instanceof Expirable e) {
            Duration duration = e.cacheDuration();
            if (duration != null) {
                // RedisTemplate has no knowledge of cache name prefixes — it stores
                // exactly the key you give it. Spring's RedisCache.get() internally
                // prepends "cacheName::" before querying Redis, so we must construct
                // the same full key here, otherwise get() won't find what put() wrote.
                String redisKey = delegate.getName() + KEY_PREFIX_SEPARATOR + key;
                redisTemplate.opsForValue().set(redisKey, value, duration);
                return;
            }
            log.warn(
                    "cache '{}': key='{}' cacheDuration() returned null — skipping cache",
                    delegate.getName(),
                    key);
            return;
        }
        log.warn(
                "cache '{}': key='{}' value of type '{}' does not implement Expirable — skipping cache",
                delegate.getName(),
                key,
                value.getClass().getName());
    }

    @Override
    public ValueWrapper get(Object key) {
        return delegate.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return delegate.get(key, valueLoader);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }
}
