package com.backyard.playground.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * {@link CacheManager} delegate for {@link CacheType#LOCAL} caches, backed by Caffeine.
 *
 * <p>
 * Reads {@link CacheDefinition} from {@link CacheDefinitionRegistry} to determine TTL and max size per cache. Supports
 * both fixed TTL and dynamic per-entry TTL (via {@link Expirable#cacheDuration()}) based on
 * {@link CacheDefinition#isDynamicTtl()}.
 *
 * <h3>Why TTL is baked in at construction, not per-put</h3>
 *
 * <p>
 * Caffeine has no per-put TTL API — TTL policy must be wired at construction time via {@link Expiry}. There is no
 * equivalent of Redis's {@code SET key value EX ttl}. Dynamic TTL is therefore handled through Caffeine's native
 * {@code expireAfter(Expiry)} mechanism, reading {@link Expirable#cacheDuration()} at insertion time.
 *
 * <p>
 * Not a Spring {@code @Component} — instantiated and registered as the {@link CacheType#LOCAL} delegate inside
 * {@link CacheConfig}.
 */
public class CaffeineBackedCacheManager implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CaffeineBackedCacheManager.class);

    private final CacheDefinitionRegistry registry;

    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public CaffeineBackedCacheManager(CacheDefinitionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Cache getCache(String name) {
        return cacheMap.computeIfAbsent(name, this::createCache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return cacheMap.keySet();
    }

    private Cache createCache(String name) {
        CacheDefinition def = registry.get(name);
        if (def == null) {
            throw new IllegalArgumentException(
                    "No CacheDefinition registered for cache name '%s'".formatted(name));
        }

        Duration localTtl = def.getLocalTtl();

        if (def.getMaxSize() == null || def.getMaxSize() <= 0) {
            throw new IllegalArgumentException(
                    "CacheDefinition '%s' must have maxSize > 0 for LOCAL caches".formatted(name));
        }
        if (!def.isDynamicTtl() && (localTtl == null || localTtl.isZero())) {
            throw new IllegalArgumentException(
                    "CacheDefinition '%s' must have a positive ttl for fixed-TTL LOCAL caches"
                            .formatted(name));
        }

        return def.isDynamicTtl()
                ? buildWithDynamicTtl(name, def.getMaxSize())
                : buildFixedTtl(name, def.getMaxSize(), localTtl.toNanos());
    }

    private CaffeineCache buildFixedTtl(String name, int maxSize, long ttlNanos) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
                .recordStats()
                .build());
    }

    private CaffeineCache buildWithDynamicTtl(String name, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<Object, Object>() {

                    // Caffeine's Expiry API does not support skipping a put —
                    // returning 0 expires the entry immediately after insertion.
                    // The entry is briefly stored but never retrievable.
                    // The warning log above will notify to fix the misconfiguration.
                    private long resolveTtl(Object key, Object value) {
                        if (value instanceof Expirable e) {
                            var duration = e.cacheDuration();
                            if (duration == null) {
                                log.warn("cache '{}': key='{}' cacheDuration() returned null"
                                        + " — expiring immediately", name, key);
                                return 0;
                            }
                            return duration.toNanos();
                        }
                        log.warn("cache '{}': key='{}' value of type '{}' does not implement"
                                + " Expirable — expiring immediately",
                                name, key, value.getClass().getName());
                        return 0;
                    }

                    @Override
                    public long expireAfterCreate(Object key, Object value, long currentTime) {
                        return resolveTtl(key, value);
                    }

                    @Override
                    public long expireAfterUpdate(Object key, Object value, long currentTime,
                            long currentDuration) {
                        // recalculate on update in case the TTL decision changed
                        return resolveTtl(key, value);
                    }

                    @Override
                    public long expireAfterRead(Object key, Object value, long currentTime,
                            long currentDuration) {
                        // reads do not reset the TTL clock
                        return currentDuration;
                    }
                })
                .recordStats()
                .build());
    }
}
