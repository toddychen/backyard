package com.backyard.playground.cache;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import io.micrometer.observation.ObservationRegistry;

/**
 * {@link CacheManager} that routes each named cache to the correct backing
 * store based on its {@link CacheDefinition}.
 *
 * <h3>Lazy creation</h3>
 *
 * <p>
 * Caches are created on first access via {@link #getCache(String)} and stored
 * in a local map. This avoids eagerly initialising all caches at startup and
 * keeps each cache's lifecycle tied to its first use.
 *
 * <h3>Wrapper chain</h3>
 *
 * <p>
 * Each delegate manager is responsible for its own internal wrapping (e.g.
 * {@link RedisBackedCacheManager} wraps with {@link RedisExpirableCache} for
 * dynamic TTL). {@link RoutingCacheManager} then wraps the result with
 * {@link InstrumentedCache} as the outermost layer, so that tracing and
 * metrics cover the full cache interaction regardless of backend.
 */
public class RoutingCacheManager implements CacheManager {

    private final CacheDefinitionRegistry registry;

    // One delegate CacheManager per CacheType, supplied at construction time
    private final Map<CacheType, CacheManager> delegates;

    private final ObservationRegistry observationRegistry;

    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public RoutingCacheManager(CacheDefinitionRegistry registry,
            Map<CacheType, CacheManager> delegates,
            ObservationRegistry observationRegistry) {
        this.registry = registry;
        this.delegates = delegates;
        this.observationRegistry = observationRegistry;
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

        CacheManager delegate = delegates.get(def.getType());
        if (delegate == null) {
            throw new IllegalStateException(
                    "No CacheManager registered for CacheType '%s'".formatted(def.getType()));
        }

        Cache cache = delegate.getCache(name);
        if (cache == null) {
            throw new IllegalStateException(
                    "Delegate CacheManager for type '%s' returned null for cache '%s'"
                            .formatted(def.getType(), name));
        }

        // Instrumentation is always the outermost wrapper so that hit/miss
        // observations cover the full cache interaction including any inner wrappers.
        return new InstrumentedCache(cache, observationRegistry);
    }
}
