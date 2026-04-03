package com.backyard.playground.cache;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Cache configuration for the {@code home} profile.
 *
 * <p>
 * Redis is not available in this profile. All cache types (LOCAL, DISTRIBUTED,
 * MULTI_LEVEL) are routed to the local Caffeine cache instead.
 */
@Configuration
@Profile("home")
@EnableCaching
public class CacheConfigForHomeProfile {

    @Bean
    @Primary
    public CacheManager cacheManager(
            CacheDefinitionRegistry registry, ObservationRegistry observationRegistry) {
        var caffeine = new CaffeineBackedCacheManager(registry);
        return new RoutingCacheManager(
                registry,
                Map.of(
                        CacheType.LOCAL, caffeine,
                        CacheType.DISTRIBUTED, caffeine,
                        CacheType.MULTI_LEVEL, caffeine),
                observationRegistry);
    }
}
