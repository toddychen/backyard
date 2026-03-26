package com.backyard.playground.cache;

import java.time.Duration;

/**
 * Marks a cacheable value as self-aware of its own TTL (time-to-live).
 *
 * <p>
 * Named {@code Expirable} following the Java convention of {@code -able} suffix for capability interfaces (e.g.
 * {@link Comparable}, {@link Iterable}, {@link java.io.Serializable}). The name was chosen over alternatives for the
 * following reasons:
 * <ul>
 * <li>{@code Cacheable} — conflicts with Spring's {@code @Cacheable} annotation</li>
 * <li>{@code ICacheable} — {@code I}-prefix is a C#/.NET convention, not idiomatic Java</li>
 * <li>{@code TtlAware} — overly technical acronym, less readable</li>
 * <li>{@code CacheDirective} — implies a command rather than a capability</li>
 * </ul>
 *
 * <p>
 * Implement this interface on any value object whose TTL should vary per instance rather than being fixed globally at
 * cache construction time. The cache infrastructure calls {@link #cacheDuration()} on each value at insertion time to
 * determine how long that specific entry should live.
 *
 */
public interface Expirable {

    /**
     * Returns how long this value should remain in the cache after being written.
     *
     * @return a positive {@link Duration} representing the TTL for this specific instance
     */
    Duration cacheDuration();
}
