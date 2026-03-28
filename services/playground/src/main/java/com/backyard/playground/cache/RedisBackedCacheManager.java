package com.backyard.playground.cache;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * {@link CacheManager} delegate for {@link CacheType#DISTRIBUTED} caches, backed by Redis.
 *
 * <p>
 * Uses composition — delegates cache creation to an injected {@link RedisCacheManager} (pre-configured with per-cache
 * TTLs from {@link CacheDefinitionRegistry} in {@link CacheConfig}), then wraps the result with
 * {@link RedisExpirableCache} for caches that require dynamic per-entry TTL.
 *
 * <h3>Serializer</h3>
 *
 * <p>
 * Both the {@link RedisCacheManager} (used for gets) and the {@link RedisTemplate} (used by {@link RedisExpirableCache}
 * for dynamic TTL puts) share the same {@link RedisSerializer} instance, ensuring values written by the decorator are
 * always readable by the normal get path.
 *
 * <p>
 * Not a Spring {@code @Component} — instantiated and registered as the {@link CacheType#DISTRIBUTED} delegate inside
 * {@link CacheConfig}.
 */
public class RedisBackedCacheManager implements CacheManager {

    static final RedisSerializer<Object> VALUE_SERIALIZER = GenericJacksonJsonRedisSerializer.builder()
            .enableUnsafeDefaultTyping()
            .enableSpringCacheNullValueSupport()
            .build();

    private final RedisCacheManager delegate;
    private final CacheDefinitionRegistry registry;
    private final RedisTemplate<String, Object> redisTemplate;

    // Separate map for dynamic TTL wrappers — the delegate's internal map holds
    // the base RedisCache instances; this map holds the RedisExpirableCache
    // wrappers on top of them.
    private final Map<String, Cache> dynamicCacheMap = new ConcurrentHashMap<>();

    public RedisBackedCacheManager(RedisCacheManager delegate,
            CacheDefinitionRegistry registry,
            RedisTemplate<String, Object> redisTemplate) {
        this.delegate = delegate;
        this.registry = registry;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        if (cache == null)
            return null;
        CacheDefinition def = registry.get(name);
        if (def != null && def.isDynamicTtl()) {
            return dynamicCacheMap.computeIfAbsent(name,
                    n -> new RedisExpirableCache((RedisCache) cache, redisTemplate));
        }
        return cache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
