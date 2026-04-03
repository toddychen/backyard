package com.backyard.playground.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CacheManager} delegate for {@link CacheType#MULTI_LEVEL} caches.
 *
 * <p>
 * Combines a LOCAL (Caffeine) cache and a DISTRIBUTED (Redis) cache into a
 * single {@link MultiLevelCache} per registered cache name.
 *
 * <p>
 * Not a Spring {@code @Component} — instantiated and registered as the
 * {@link CacheType#MULTI_LEVEL} delegate inside {@link CacheConfig}.
 */
public class MultiLevelCacheManager implements CacheManager {

    private final CacheDefinitionRegistry registry;
    private final CaffeineBackedCacheManager localManager;
    private final RedisBackedCacheManager distributedManager;

    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public MultiLevelCacheManager(
            CacheDefinitionRegistry registry,
            CaffeineBackedCacheManager localManager,
            RedisBackedCacheManager distributedManager) {
        this.registry = registry;
        this.localManager = localManager;
        this.distributedManager = distributedManager;
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
        Cache local = localManager.getCache(name);
        Cache distributed = distributedManager.getCache(name);
        return new MultiLevelCache(name, local, distributed);
    }
}
