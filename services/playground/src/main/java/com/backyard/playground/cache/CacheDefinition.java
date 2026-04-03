package com.backyard.playground.cache;

import java.time.Duration;

/**
 * Declares the configuration for a single named cache — type, TTL, and optional
 * feature flags. All caches are registered in {@link CacheDefinitionRegistry}.
 *
 * <p>
 * {@code dynamicTtl} signals that cached values implement {@link Expirable} and
 * carry their own per-entry TTL. When true, the cache infrastructure reads
 * {@link Expirable#cacheDuration()} at insertion time to determine the TTL for
 * each entry individually.
 *
 * <p>
 * {@code maxSize} is only meaningful for caches with a LOCAL component
 * (Caffeine). {@link CacheType#DISTRIBUTED} caches ignore it — Redis relies on
 * TTL-based eviction and memory limits instead.
 *
 * <p>
 * {@link CacheType#MULTI_LEVEL} caches hold separate TTLs for the local and
 * distributed layers — use {@link #getLocalTtl()} and
 * {@link #getDistributedTtl()} respectively.
 *
 * <p>
 * Use the static factory methods to construct definitions without needing to
 * supply irrelevant fields.
 */
public class CacheDefinition {

    private final String name;
    private final CacheType type;
    private final Duration localTtl;
    private final Duration distributedTtl;
    private final boolean dynamicTtl;
    private final Integer maxSize;

    private CacheDefinition(
            String name,
            CacheType type,
            Duration localTtl,
            Duration distributedTtl,
            boolean dynamicTtl,
            Integer maxSize) {
        this.name = name;
        this.type = type;
        this.localTtl = localTtl;
        this.distributedTtl = distributedTtl;
        this.dynamicTtl = dynamicTtl;
        this.maxSize = maxSize;
    }

    /** Fixed-TTL LOCAL (Caffeine) cache. */
    public static CacheDefinition local(String name, Duration ttl, int maxSize) {
        return new CacheDefinition(name, CacheType.LOCAL, ttl, null, false, maxSize);
    }

    /** Dynamic per-entry TTL LOCAL (Caffeine) cache via {@link Expirable}. */
    public static CacheDefinition localDynamic(String name, int maxSize) {
        return new CacheDefinition(name, CacheType.LOCAL, null, null, true, maxSize);
    }

    /** Fixed-TTL DISTRIBUTED (Redis) cache. */
    public static CacheDefinition distributed(String name, Duration ttl) {
        return new CacheDefinition(name, CacheType.DISTRIBUTED, null, ttl, false, null);
    }

    /** Dynamic per-entry TTL DISTRIBUTED (Redis) cache via {@link Expirable}. */
    public static CacheDefinition distributedDynamic(String name) {
        return new CacheDefinition(name, CacheType.DISTRIBUTED, null, null, true, null);
    }

    /**
     * Fixed-TTL MULTI_LEVEL cache: LOCAL (Caffeine) in front of DISTRIBUTED
     * (Redis), each with its own TTL.
     *
     * @param localTtl       TTL for the local layer (Caffeine); should be shorter
     *                       than {@code
     *     distributedTtl} to bound stale reads
     * @param distributedTtl TTL for the distributed layer (Redis)
     * @param maxSize        max entries in the local Caffeine cache
     */
    public static CacheDefinition multiLevel(
            String name, Duration localTtl, Duration distributedTtl, int maxSize) {
        return new CacheDefinition(
                name, CacheType.MULTI_LEVEL, localTtl, distributedTtl, false, maxSize);
    }

    /**
     * Dynamic per-entry TTL MULTI_LEVEL cache via {@link Expirable}. Both levels
     * read {@link Expirable#cacheDuration()} from the value at insertion time.
     */
    public static CacheDefinition multiLevelDynamic(String name, int maxSize) {
        return new CacheDefinition(name, CacheType.MULTI_LEVEL, null, null, true, maxSize);
    }

    public String getName() {
        return name;
    }

    public CacheType getType() {
        return type;
    }

    /**
     * Returns the local TTL for LOCAL and MULTI_LEVEL caches (null when dynamic).
     */
    public Duration getLocalTtl() {
        return localTtl;
    }

    /** Returns the distributed TTL for MULTI_LEVEL caches (null when dynamic). */
    public Duration getDistributedTtl() {
        return distributedTtl;
    }

    public boolean isDynamicTtl() {
        return dynamicTtl;
    }

    public Integer getMaxSize() {
        return maxSize;
    }
}
