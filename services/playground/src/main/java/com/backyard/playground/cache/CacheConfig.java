package com.backyard.playground.cache;

import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import io.micrometer.observation.ObservationRegistry;

/**
 * Wires all cache backends into a single {@link RoutingCacheManager}.
 *
 * <p>
 * To add a new cache: declare a name constant here, register a {@link CacheDefinition} in
 * {@link CacheDefinitionRegistry}, then ensure the appropriate backend bean is present for its {@link CacheType}.
 */
@Configuration
@Profile("!home")
@EnableCaching
public class CacheConfig {

    // Cache name constants — reference these in @Cacheable annotations to avoid
    // magic strings
    public static final String CACHE_POLLEN = "pollen";
    public static final String CACHE_GAME_DETAILS = "game-details";

    @Bean
    @Primary
    public CacheManager cacheManager(CacheDefinitionRegistry registry,
            CaffeineBackedCacheManager caffeineBackedCacheManager,
            RedisBackedCacheManager redisBackedCacheManager,
            MultiLevelCacheManager multiLevelCacheManager,
            ObservationRegistry observationRegistry) {
        return new RoutingCacheManager(
                registry,
                Map.of(
                        CacheType.LOCAL, caffeineBackedCacheManager,
                        CacheType.DISTRIBUTED, redisBackedCacheManager,
                        CacheType.MULTI_LEVEL, multiLevelCacheManager),
                observationRegistry);
    }

    @Bean
    public CaffeineBackedCacheManager caffeineBackedCacheManager(CacheDefinitionRegistry registry) {
        return new CaffeineBackedCacheManager(registry);
    }

    @Bean
    public MultiLevelCacheManager multiLevelCacheManager(CacheDefinitionRegistry registry,
            CaffeineBackedCacheManager caffeineBackedCacheManager,
            RedisBackedCacheManager redisBackedCacheManager) {
        return new MultiLevelCacheManager(
                registry, caffeineBackedCacheManager, redisBackedCacheManager);
    }

    @Bean
    public RedisBackedCacheManager redisBackedCacheManager(RedisCacheManager redisCacheManager,
            CacheDefinitionRegistry registry,
            RedisTemplate<String, Object> redisTemplate) {
        return new RedisBackedCacheManager(redisCacheManager, registry, redisTemplate);
    }

    /**
     * Pre-populates {@link RedisCacheManager} with all DISTRIBUTED and MULTI_LEVEL
     * cache configurations from the registry. Spring manages lifecycle and calls
     * {@code afterPropertiesSet()} automatically.
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory,
            CacheDefinitionRegistry registry) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisBackedCacheManager.VALUE_SERIALIZER));

        var builder = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig);

        // Dynamic TTL caches are excluded — their TTL is set per-entry at runtime
        // via Expirable#cacheDuration() in RedisExpirableCache, not via config.
        // MULTI_LEVEL caches share the same Redis backend, so they are included.
        registry.all().stream()
                .filter(def -> CacheType.DISTRIBUTED_BACKED.contains(def.getType())
                        && !def.isDynamicTtl())
                .forEach(def -> builder.withCacheConfiguration(
                        def.getName(), defaultConfig.entryTtl(def.getDistributedTtl())));

        return builder.build();
    }

    /**
     * Shared {@link RedisTemplate} used by {@link RedisExpirableCache} for dynamic TTL puts. Uses the same serializer
     * as {@link RedisCacheManager} so values are mutually readable.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisBackedCacheManager.VALUE_SERIALIZER);
        return template;
    }
}
