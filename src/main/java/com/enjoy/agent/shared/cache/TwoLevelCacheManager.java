package com.enjoy.agent.shared.cache;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * 仅为明确配置的高频缓存启用两级策略，其他缓存仍交给 Redis CacheManager。
 */
public final class TwoLevelCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final Map<String, Cache> twoLevelCaches;

    public TwoLevelCacheManager(CacheManager delegate, Collection<? extends Cache> twoLevelCaches) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(twoLevelCaches, "twoLevelCaches");
        this.twoLevelCaches = twoLevelCaches.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Cache::getName,
                cache -> cache
        ));
    }

    @Override
    public Cache getCache(String name) {
        Cache twoLevel = twoLevelCaches.get(name);
        return twoLevel == null ? delegate.getCache(name) : twoLevel;
    }

    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(delegate.getCacheNames());
        names.addAll(twoLevelCaches.keySet());
        return Set.copyOf(names);
    }
}
