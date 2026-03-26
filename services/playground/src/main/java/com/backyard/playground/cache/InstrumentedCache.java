package com.backyard.playground.cache;

import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Wraps any {@link Cache} delegate with two cross-cutting concerns:
 *
 * <p>
 * <b>Tracing:</b> creates a {@code cache.get} span (tagged with {@code cache.hit=true/false}) and a {@code cache.put}
 * span for every operation, visible in Jaeger as child spans of the active request trace.
 *
 * <p>
 * <b>Logging:</b> logs cache hits and misses at DEBUG level — enable in dev by setting
 * {@code logging.level.com.backyard.playground.cache=DEBUG}. For Caffeine caches, hit logs include remaining TTL (free,
 * in-memory lookup). For Redis, TTL is omitted to avoid an extra network round-trip per hit.
 */
public class InstrumentedCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(InstrumentedCache.class);

    private final Cache delegate;
    private final ObservationRegistry observationRegistry;
    private final String cacheType;

    public InstrumentedCache(Cache delegate, ObservationRegistry observationRegistry) {
        this.delegate = delegate;
        this.observationRegistry = observationRegistry;
        this.cacheType = delegate.getClass().getSimpleName();
    }

    @Override
    public ValueWrapper get(Object key) {
        Observation obs = Observation
                .createNotStarted("cache.get", observationRegistry)
                .lowCardinalityKeyValue("cache.type", cacheType)
                .lowCardinalityKeyValue("cache.name", delegate.getName())
                .highCardinalityKeyValue("cache.key", String.valueOf(key))
                .start();
        ValueWrapper result = delegate.get(key);
        obs.lowCardinalityKeyValue("cache.result", result != null ? "hit" : "miss").stop();

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

    @Override
    public void put(Object key, Object value) {
        Observation obs = Observation
                .createNotStarted("cache.put", observationRegistry)
                .lowCardinalityKeyValue("cache.type", cacheType)
                .lowCardinalityKeyValue("cache.name", delegate.getName())
                .highCardinalityKeyValue("cache.key", String.valueOf(key))
                .start();
        delegate.put(key, value);
        obs.stop();
    }

    /**
     * Returns remaining TTL in seconds for the given key.
     *
     * <p>
     * Caffeine: resolved from the in-memory cache policy — free, no I/O.
     * <p>
     * Redis: not implemented — querying TTL requires a separate network call ({@code TTL key}), which is too expensive
     * to do on every cache hit just for a log line. Returns empty; callers log the hit without TTL detail.
     */
    @SuppressWarnings("unchecked")
    private OptionalLong remainingSeconds(Object key) {
        var nativeCache = delegate.getNativeCache();

        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
            var policy = ((com.github.benmanes.caffeine.cache.Cache<Object, Object>) caffeine).policy();

            var varExpiry = policy.expireVariably();
            if (varExpiry.isPresent()) {
                return varExpiry.get().getExpiresAfter(key, TimeUnit.SECONDS);
            }

            var writeExpiry = policy.expireAfterWrite();
            if (writeExpiry.isPresent()) {
                long ttl = writeExpiry.get().getExpiresAfter(TimeUnit.SECONDS);
                OptionalLong age = writeExpiry.get().ageOf(key, TimeUnit.SECONDS);
                if (age.isPresent()) {
                    return OptionalLong.of(ttl - age.getAsLong());
                }
            }
        }

        // TODO: Redis — skip TTL lookup to avoid extra network round-trip

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
