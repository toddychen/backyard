package com.backyard.playground.cache;

import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

/**
 * A diagnostic wrapper around a {@link Cache} delegate. All cache operations pass through to the underlying cache
 * unchanged — this class adds no caching logic of its own.
 *
 * <p>
 * Its only purpose is to log cache hits and misses on {@link #get} to help diagnose whether the cache is working as
 * expected during development. Enable via {@code cache.diagnostic.enabled=true} — should never be active in stage/prod.
 */
public class DiagnosticCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticCache.class);

    private final Cache delegate;

    public DiagnosticCache(Cache delegate) {
        this.delegate = delegate;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper result = delegate.get(key);
        if (result == null) {
            log.debug("cache MISS: cache='{}' key='{}'", delegate.getName(), key);
        } else {
            OptionalLong remaining = remainingSeconds(key);
            if (remaining.isPresent()) {
                log.debug("cache HIT:  cache='{}' key='{}' expires in {}s",
                        delegate.getName(), key, remaining.getAsLong());
            } else {
                log.debug("cache HIT:  cache='{}' key='{}'", delegate.getName(), key);
            }
        }
        return result;
    }

    /**
     * Resolves remaining TTL in seconds for the given key from the underlying Caffeine cache policy. Handles both
     * variable-TTL caches (per-entry expiry) and fixed-TTL caches (expireAfterWrite). Returns empty if TTL cannot be
     * determined.
     */
    @SuppressWarnings("unchecked")
    private OptionalLong remainingSeconds(Object key) {
        var native_ = (com.github.benmanes.caffeine.cache.Cache<Object, Object>) delegate.getNativeCache();
        var policy = native_.policy();

        // variable TTL — each entry has its own expiry (e.g. game-details)
        var varExpiry = policy.expireVariably();
        if (varExpiry.isPresent()) {
            return varExpiry.get().getExpiresAfter(key, TimeUnit.SECONDS);
        }

        // fixed TTL — all entries share the same TTL; compute remaining from age
        var writeExpiry = policy.expireAfterWrite();
        if (writeExpiry.isPresent()) {
            long ttl = writeExpiry.get().getExpiresAfter(TimeUnit.SECONDS);
            OptionalLong age = writeExpiry.get().ageOf(key, TimeUnit.SECONDS);
            if (age.isPresent()) {
                return OptionalLong.of(ttl - age.getAsLong());
            }
        }

        return OptionalLong.empty();
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return delegate.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }
}
