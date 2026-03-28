package com.backyard.playground.cache;

import java.util.concurrent.Callable;

import org.springframework.cache.Cache;

/**
 * Two-level cache: checks LOCAL (Caffeine) first, falls through to DISTRIBUTED (Redis) on miss, and populates the local
 * cache on a distributed hit.
 *
 * <p>
 * Writes go to both levels so the local cache is always warm. Evictions and clears are propagated to both levels to
 * prevent stale reads.
 *
 * <p>
 * Not a Spring {@code @Component} — built by {@link MultiLevelCacheManager} per registered cache name.
 */
public class MultiLevelCache implements Cache {

    private final String name;
    private final Cache localDelegate;
    private final Cache distributedDelegate;

    public MultiLevelCache(String name, Cache localDelegate, Cache distributedDelegate) {
        this.name = name;
        this.localDelegate = localDelegate;
        this.distributedDelegate = distributedDelegate;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper local = localDelegate.get(key);
        if (local != null) {
            return local;
        }
        ValueWrapper distributed = distributedDelegate.get(key);
        if (distributed != null) {
            // Populate local cache on distributed hit
            localDelegate.put(key, distributed.get());
        }
        return distributed;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        T local = localDelegate.get(key, type);
        if (local != null) {
            return local;
        }
        T distributed = distributedDelegate.get(key, type);
        if (distributed != null) {
            localDelegate.put(key, distributed);
        }
        return distributed;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        // Check local first, then distributed, then call loader
        ValueWrapper local = localDelegate.get(key);
        if (local != null) {
            @SuppressWarnings("unchecked")
            T value = (T) local.get();
            return value;
        }
        ValueWrapper distributed = distributedDelegate.get(key);
        if (distributed != null) {
            @SuppressWarnings("unchecked")
            T value = (T) distributed.get();
            localDelegate.put(key, value);
            return value;
        }
        // Miss on both levels — load and populate both
        T loaded = distributedDelegate.get(key, valueLoader);
        localDelegate.put(key, loaded);
        return loaded;
    }

    // putIfAbsent is intentionally not overridden. The default Cache
    // interface implementation is a non-atomic get-then-put, meaning two
    // threads can both observe a miss and both write. RedisCache overrides
    // this with SET NX (atomic), but coordinating that result back to the
    // local level adds complexity. Whether to override depends on system
    // requirements — if strict once-only writes are needed, delegate to
    // distributedDelegate.putIfAbsent() and sync the result to local.

    @Override
    public void put(Object key, Object value) {
        distributedDelegate.put(key, value);
        localDelegate.put(key, value);
    }

    @Override
    public void evict(Object key) {
        distributedDelegate.evict(key);
        localDelegate.evict(key);
    }

    @Override
    public void clear() {
        distributedDelegate.clear();
        localDelegate.clear();
    }
}
