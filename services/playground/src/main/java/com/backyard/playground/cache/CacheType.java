package com.backyard.playground.cache;

import java.util.EnumSet;
import java.util.Set;

/**
 * Identifies the backing store for a cache entry declared in
 * {@link CacheDefinitionRegistry}.
 */
public enum CacheType {

    /** In-memory cache - currently backed by Caffeine. */
    LOCAL,

    /** Distributed cache — currently backed by Redis. */
    DISTRIBUTED,

    /** Two-level cache: LOCAL in front of DISTRIBUTED. */
    MULTI_LEVEL;

    /**
     * Cache types that involve a distributed backend and therefore require a {@code
     * RedisCacheManager} configuration entry.
     */
    public static final Set<CacheType> DISTRIBUTED_BACKED = EnumSet.of(DISTRIBUTED, MULTI_LEVEL);
}
