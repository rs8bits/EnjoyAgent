package com.enjoy.agent.shared.cache;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A local-only cache whose values never cross the Redis trust boundary.
 *
 * <p>Redis Pub/Sub is used only as a best-effort invalidation signal. A forged message can evict a
 * value (at worst causing a database reload), but can never inject an authorization object. The
 * local expiry remains the hard upper bound when the invalidation bus is unavailable.</p>
 */
public final class InvalidatingLocalCache implements org.springframework.cache.Cache, AutoCloseable {

    public static final String CLEAR_ALL_MESSAGE = "__all__";
    private static final Logger log = LoggerFactory.getLogger(InvalidatingLocalCache.class);

    private final String name;
    private final Cache<Object, Object> localCache;
    private final CacheInvalidationBus invalidationBus;
    private final CacheInvalidationBus.Subscription subscription;
    private final AtomicLong clearEpoch = new AtomicLong();

    public InvalidatingLocalCache(
            String name,
            Cache<Object, Object> localCache,
            CacheInvalidationBus invalidationBus
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.localCache = Objects.requireNonNull(localCache, "localCache");
        this.invalidationBus = Objects.requireNonNull(invalidationBus, "invalidationBus");
        this.subscription = invalidationBus.subscribe(name, this::onRemoteInvalidation);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return localCache;
    }

    @Override
    public ValueWrapper get(Object key) {
        Object value = currentValue(key);
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        Object value = currentValue(key);
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return type == null ? uncheckedCast(value) : type.cast(value);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Epoch loadEpoch = epoch(key);
            try {
                LocalEntry entry = uncheckedCast(localCache.get(key, ignored -> {
                    try {
                        T loaded = valueLoader.call();
                        return loaded == null ? null : new LocalEntry(loaded, loadEpoch);
                    } catch (RuntimeException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new LoaderException(ex);
                    }
                }));
                if (entry == null) {
                    return null;
                }
                if (entry.epoch().equals(epoch(key))) {
                    return uncheckedCast(entry.value());
                }
                localCache.asMap().remove(key, entry);
            } catch (LoaderException ex) {
                throw new ValueRetrievalException(key, valueLoader, ex.getCause());
            }
        }
        try {
            return valueLoader.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (value != null) {
            localCache.put(key, new LocalEntry(value, epoch(key)));
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        if (value == null) {
            return get(key);
        }
        Object existing = currentValue(key);
        if (existing != null) {
            return new SimpleValueWrapper(existing);
        }
        LocalEntry candidate = new LocalEntry(value, epoch(key));
        Object raced = localCache.asMap().putIfAbsent(key, candidate);
        if (raced == null) {
            return null;
        }
        Object racedValue = currentValue(key);
        return racedValue == null ? null : new SimpleValueWrapper(racedValue);
    }

    @Override
    public void evict(Object key) {
        afterCommit(() -> {
            // Authorization mutations are rare. A single global epoch prevents both
            // stale in-flight loads and an unbounded per-principal epoch registry.
            clearEpoch.incrementAndGet();
            // Do not call Caffeine.invalidate while a mapping function is running: it waits for
            // that computation and can turn a security update into a request-thread stall. The
            // epoch makes the old/in-flight value immediately unreadable; the next lookup removes it.
            publish(CLEAR_ALL_MESSAGE);
        });
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean present = currentValue(key) != null;
        evict(key);
        return present;
    }

    @Override
    public void clear() {
        afterCommit(() -> {
            clearEpoch.incrementAndGet();
            publish(CLEAR_ALL_MESSAGE);
        });
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    private void publish(String key) {
        try {
            invalidationBus.publish(name, key);
        } catch (RuntimeException ex) {
            log.warn("Local cache invalidation publish failed, cache={}, reason={}", name, ex.toString());
        }
    }

    private void onRemoteInvalidation(String key) {
        // This cache contains authorization snapshots only. Treat legacy/key-specific
        // messages as a full epoch bump as well, so no unbounded key metadata is retained.
        clearEpoch.incrementAndGet();
    }

    private Object currentValue(Object key) {
        Object cached = localCache.getIfPresent(key);
        if (!(cached instanceof LocalEntry entry)) {
            return null;
        }
        if (!entry.epoch().equals(epoch(key))) {
            localCache.asMap().remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private Epoch epoch(Object key) {
        return new Epoch(clearEpoch.get());
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    public void close() {
        subscription.close();
        localCache.invalidateAll();
    }

    @SuppressWarnings("unchecked")
    private static <T> T uncheckedCast(Object value) {
        return (T) value;
    }

    private static final class LoaderException extends RuntimeException {
        private LoaderException(Throwable cause) {
            super(cause);
        }
    }

    private record Epoch(long clear) {
    }

    private record LocalEntry(Object value, Epoch epoch) {
    }
}
