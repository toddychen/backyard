package com.backyard.playground.cache;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * Single source of truth for all named caches in the application.
 *
 * <p>
 * To add a new cache: declare a constant in {@link CacheConfig} for the name,
 * then register a {@link CacheDefinition} here with its type, TTL, and flags.
 * {@link RoutingCacheManager} reads this registry to create and configure each
 * cache on first use.
 */
@Component
public class CacheDefinitionRegistry {

    private final Map<String, CacheDefinition> definitions = Map.of(
            CacheConfig.CACHE_POLLEN,
            CacheDefinition.distributed(CacheConfig.CACHE_POLLEN, Duration.ofMinutes(10)),
            CacheConfig.CACHE_GAME_DETAILS,
            CacheDefinition.multiLevelDynamic(CacheConfig.CACHE_GAME_DETAILS, 500)

    // examples of MULTI_LEVEL caches, to be activated later:
    //
    // fixed TTL — local 1 min, distributed 10 min, max 500 entries
    // CacheConfig.CACHE_WEATHER,
    // CacheDefinition.multiLevel(CacheConfig.CACHE_WEATHER,
    // Duration.ofMinutes(1), Duration.ofMinutes(10), 500)
    //
    // dynamic TTL — both levels read Expirable#cacheDuration()
    // CacheConfig.CACHE_SPORT,
    // CacheDefinition.multiLevelDynamic(CacheConfig.CACHE_SPORT, 200)
    );

    public CacheDefinition get(String name) {
        return definitions.get(name);
    }

    public Collection<CacheDefinition> all() {
        return definitions.values();
    }
}
