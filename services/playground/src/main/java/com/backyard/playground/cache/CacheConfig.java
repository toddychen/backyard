package com.backyard.playground.cache;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

@Configuration
@EnableCaching
public class CacheConfig {

    // Cache name constants — reference these in @Cacheable annotations to avoid
    // magic strings
    public static final String CACHE_POLLEN = "pollen";
    public static final String CACHE_GAME_DETAILS = "game-details";

    // Enable in dev via cache.diagnostic.enabled=true — off by default so no
    // wrapper overhead in stage/prod
    @Value("${cache.diagnostic.enabled:false}")
    private boolean cacheLoggingEnabled;

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                // Pollen data from Ambee updates a few times per day — 10 min local TTL is safe
                diagnosticWrap(buildCache(CACHE_POLLEN, 100, 10, TimeUnit.MINUTES)),
                // Game details TTL varies: 10s when game is live, 60s otherwise (via Expirable)
                diagnosticWrap(buildCacheWithDynamicTtl(CACHE_GAME_DETAILS, 100))));
        return manager;
    }

    // Wraps a cache with hit/miss logging when cache.diagnostic.enabled=true.
    // Returns the cache unwrapped in stage/prod to avoid any overhead.
    private Cache diagnosticWrap(CaffeineCache cache) {
        return cacheLoggingEnabled ? new DiagnosticCache(cache) : cache;
    }

    /**
     * Builds a fixed-TTL cache — all entries expire after the same duration. Use this when TTL is uniform for a given
     * cache (e.g. all forecast data expires in 1h).
     */
    private CaffeineCache buildCache(String name, int maxSize, long ttl, TimeUnit unit) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(ttl, unit)
                        .recordStats() // exposes hit/miss rates to Micrometer for monitoring
                        .build());
    }

    /**
     * Builds a dynamic-TTL cache where each entry's expiry is determined by the cached value's own
     * {@link Expirable#cacheDuration()} — allowing TTL to vary per instance. Use this when TTL depends on runtime
     * properties of the value (e.g. city region).
     *
     * Uses {@code Expiry<Object, Object>} with instanceof pattern matching because Caffeine's builder cannot infer
     * bounded generic type parameters at construction time — Spring's CaffeineCache wraps a raw
     * {@code Cache<Object, Object>} internally anyway.
     */
    private CaffeineCache buildCacheWithDynamicTtl(String name, int maxSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfter(new Expiry<Object, Object>() {
                            private final Logger log = LoggerFactory.getLogger(CacheConfig.class);

                            private long resolveTtl(Object key, Object value) {
                                if (value instanceof Expirable e) {
                                    var duration = e.cacheDuration();
                                    if (duration == null) {
                                        log.warn(
                                                "cache '{}': key='{}' cacheDuration() returned null — expiring immediately",
                                                name, key);
                                        return 0;
                                    }
                                    return duration.toNanos();
                                }
                                log.warn(
                                        "cache '{}': key='{}' value of type '{}' does not implement Expirable — expiring immediately",
                                        name, key, value.getClass().getName());
                                return 0; // expire immediately — value must implement Expirable to be cached
                            }

                            @Override
                            public long expireAfterCreate(Object key, Object value, long currentTime) {
                                return resolveTtl(key, value);
                            }

                            @Override
                            public long expireAfterUpdate(Object key, Object value,
                                    long currentTime, long currentDuration) {
                                // recalculate on update in case the TTL decision changed
                                return resolveTtl(key, value);
                            }

                            @Override
                            public long expireAfterRead(Object key, Object value,
                                    long currentTime, long currentDuration) {
                                // reads do not reset the TTL clock
                                return currentDuration;
                            }
                        })
                        .recordStats() // exposes hit/miss rates to Micrometer for monitoring
                        .build());
    }
}
