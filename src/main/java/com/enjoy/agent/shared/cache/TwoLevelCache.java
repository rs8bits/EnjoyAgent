package com.enjoy.agent.shared.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Caffeine L1 + Redis L2 缓存。
 *
 * <p>L1 负责极低延迟与单实例 single-flight，L2 负责跨实例共享。对热点缓存可选择在
 * L1/L2 同时 miss 时启用分布式回源保护。</p>
 */
public final class TwoLevelCache implements Cache, AutoCloseable {

    public static final String CLEAR_ALL_MESSAGE = "__all__";
    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final String LOAD_LOCK_PREFIX = "enjoy:cache-load:";

    private final String name;
    private final Cache remoteCache;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> localCache;
    private final boolean localEnabled;
    private final boolean distributedLockEnabled;
    private final CacheLoadGuard loadGuard;
    private final CacheGenerationStore generationStore;
    private final CacheInvalidationBus invalidationBus;
    private final CacheInvalidationBus.Subscription subscription;
    private final CacheMetrics metrics;
    private final ScheduledExecutorService invalidationRetryExecutor;
    private final AtomicLong localClearEpoch = new AtomicLong();
    private final AtomicLong invalidationSequence = new AtomicLong();
    private final ConcurrentMap<Object, PendingInvalidation> pendingKeyInvalidations = new ConcurrentHashMap<>();
    private final AtomicReference<PendingInvalidation> pendingClearInvalidation = new AtomicReference<>();

    public TwoLevelCache(
            String name,
            Cache remoteCache,
            com.github.benmanes.caffeine.cache.Cache<Object, Object> localCache,
            boolean localEnabled,
            boolean distributedLockEnabled,
            CacheLoadGuard loadGuard,
            CacheInvalidationBus invalidationBus,
            MeterRegistry meterRegistry
    ) {
        this(
                name,
                remoteCache,
                localCache,
                localEnabled,
                distributedLockEnabled,
                loadGuard,
                CacheGenerationStore.disabled(),
                invalidationBus,
                meterRegistry
        );
    }

    public TwoLevelCache(
            String name,
            Cache remoteCache,
            com.github.benmanes.caffeine.cache.Cache<Object, Object> localCache,
            boolean localEnabled,
            boolean distributedLockEnabled,
            CacheLoadGuard loadGuard,
            CacheGenerationStore generationStore,
            CacheInvalidationBus invalidationBus,
            MeterRegistry meterRegistry
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.remoteCache = unwrapTransactionAwareCache(Objects.requireNonNull(remoteCache, "remoteCache"));
        this.localCache = Objects.requireNonNull(localCache, "localCache");
        this.localEnabled = localEnabled;
        this.distributedLockEnabled = distributedLockEnabled;
        if (distributedLockEnabled && loadGuard == null) {
            throw new IllegalArgumentException("loadGuard is required when distributed locking is enabled");
        }
        this.loadGuard = loadGuard;
        this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
        this.invalidationBus = Objects.requireNonNull(invalidationBus, "invalidationBus");
        this.metrics = new CacheMetrics(Objects.requireNonNull(meterRegistry, "meterRegistry"), name);
        this.subscription = invalidationBus.subscribe(name, this::onRemoteInvalidation);
        this.invalidationRetryExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("cache-invalidation-retry-" + name).unstarted(runnable));
        this.invalidationRetryExecutor.scheduleWithFixedDelay(
                this::retryPendingInvalidations,
                1,
                1,
                TimeUnit.SECONDS
        );
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
        Lookup local = lookupLocal(key);
        if (local.found()) {
            return new SimpleValueWrapper(local.value());
        }

        LocalEpoch epoch = localEpoch(key);
        RemoteLookup remote = lookupRemote(key);
        if (!remote.found()) {
            return null;
        }
        putLocal(key, remote.value(), epoch);
        if (!epoch.equals(localEpoch(key))) {
            localCache.invalidate(key);
            return null;
        }
        return new SimpleValueWrapper(remote.value());
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return type == null ? uncheckedCast(value) : type.cast(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Objects.requireNonNull(valueLoader, "valueLoader");
        if (!localEnabled) {
            return (T) loadThroughRemote(key, valueLoader).value();
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            Lookup existing = lookupLocal(key);
            if (existing.found()) {
                return (T) existing.value();
            }

            LocalEpoch loadEpoch = localEpoch(key);
            LocalEntry entry = (LocalEntry) localCache.get(key, ignored -> {
                LoadResult loaded = loadThroughRemote(key, valueLoader);
                if (loaded.value() == null) {
                    return null;
                }
                return new LocalEntry(
                        loaded.value(),
                        loadEpoch,
                        loaded.cacheableLocally(),
                        loaded.retryRequired()
                );
            });
            if (entry == null) {
                return null;
            }
            if (!entry.cacheable()) {
                localCache.asMap().remove(key, entry);
                if (entry.retryRequired()) {
                    continue;
                }
                return (T) entry.value();
            }
            if (entry.epoch().equals(localEpoch(key))) {
                return (T) entry.value();
            }
            // evict 后的新请求可能 join evict 前已经在运行的 Caffeine computation。
            // 旧 envelope 必须移除并重试，不能把旧值返回给这个新请求。
            localCache.asMap().remove(key, entry);
        }
        return (T) loadWithoutCaching(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        GenerationLookup generation = generation(key);
        if (generation.available()) {
            putRemote(remoteKey(key, generation.generation()), value);
            putLocal(key, value);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) {
            return existing;
        }
        try {
            GenerationLookup generation = generation(key);
            if (!generation.available()) {
                return null;
            }
            ValueWrapper remoteExisting = remoteCache.putIfAbsent(
                    remoteKey(key, generation.generation()),
                    value
            );
            if (remoteExisting != null) {
                putLocal(key, remoteExisting.get());
                return remoteExisting;
            }
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("L2 cache putIfAbsent failed, cache={}, reason={}", name, ex.toString());
        }
        putLocal(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        executeAfterCommit(() -> invalidateKey(key, true));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean present = (localEnabled && localCache.getIfPresent(key) != null) || lookupRemote(key).found();
        evict(key);
        return present;
    }

    @Override
    public void clear() {
        executeAfterCommit(() -> invalidateAll(true));
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    private LoadResult loadThroughRemote(Object key, Callable<?> valueLoader) {
        RemoteLookup remote = lookupRemote(key);
        if (remote.found()) {
            return LoadResult.cacheable(remote.value());
        }
        if (!distributedLockEnabled) {
            return loadAndPopulateRemote(key, valueLoader);
        }

        try {
            CacheLoadGuard.GuardResult<LoadResult> guarded = loadGuard.tryExecute(lockName(key), () -> {
                RemoteLookup doubleChecked = lookupRemote(key);
                if (doubleChecked.found()) {
                    return LoadResult.cacheable(doubleChecked.value());
                }
                return loadAndPopulateRemote(key, valueLoader);
            });
            if (guarded.acquired()) {
                metrics.lockAcquired.increment();
                return guarded.value();
            }
            metrics.lockTimeout.increment();
        } catch (CacheLoadGuardUnavailableException ex) {
            // Redis/Redisson 故障时 fail open：保留 DB 回源能力，不让缓存故障变成业务故障。
            metrics.lockError.increment();
            log.warn("Distributed cache load guard failed open, cache={}, reason={}", name, ex.toString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ValueRetrievalException(key, valueLoader, ex);
        } catch (RuntimeException ex) {
            // ApiException / BadCredentialsException 等 loader 业务异常必须保持原语义。
            throw ex;
        } catch (Exception ex) {
            // 自定义 guard 的 checked infrastructure exception 同样降级。
            metrics.lockError.increment();
            log.warn("Distributed cache load guard failed open, cache={}, reason={}", name, ex.toString());
        }

        // 等锁超时后再读一次 L2；若 winner 已完成回源，可以避免降级查库。
        RemoteLookup afterWait = lookupRemote(key);
        return afterWait.found()
                ? LoadResult.cacheable(afterWait.value())
                : loadAndPopulateRemote(key, valueLoader);
    }

    private LoadResult loadAndPopulateRemote(Object key, Callable<?> valueLoader) {
        Timer.Sample sample = Timer.start(metrics.registry);
        try {
            // 锁等待或二次检查期间 generation 可能已经变化；在真正回源前重新捕获。
            GenerationLookup generation = generation(key);
            Object loaded = valueLoader.call();
            metrics.loadSuccess.increment();
            if (loaded == null) {
                return LoadResult.cacheable(null);
            }

            GenerationCheck check = checkGeneration(key, generation);
            if (check.available() && check.current()) {
                putRemote(remoteKey(key, generation.generation()), loaded);
            }
            // generation 已推进或 generation 服务不可用时，结果可以返回给当前请求，
            // 但不得进入 L1/L2，避免故障恢复后留下不可判定的新旧值。
            return new LoadResult(
                    loaded,
                    check.available() && check.current(),
                    check.available() && !check.current()
            );
        } catch (RuntimeException ex) {
            metrics.loadFailure.increment();
            throw ex;
        } catch (Exception ex) {
            metrics.loadFailure.increment();
            throw new ValueRetrievalException(key, valueLoader, ex);
        } finally {
            sample.stop(metrics.loadTimer);
        }
    }

    private Object loadWithoutCaching(Object key, Callable<?> valueLoader) {
        Timer.Sample sample = Timer.start(metrics.registry);
        try {
            Object loaded = valueLoader.call();
            metrics.loadSuccess.increment();
            return loaded;
        } catch (RuntimeException ex) {
            metrics.loadFailure.increment();
            throw ex;
        } catch (Exception ex) {
            metrics.loadFailure.increment();
            throw new ValueRetrievalException(key, valueLoader, ex);
        } finally {
            sample.stop(metrics.loadTimer);
        }
    }

    private Lookup lookupLocal(Object key) {
        if (!localEnabled) {
            return Lookup.missing();
        }
        Object cached = localCache.getIfPresent(key);
        if (cached == null) {
            metrics.localMiss.increment();
            return Lookup.missing();
        }
        if (cached instanceof LocalEntry entry) {
            if (!entry.cacheable() || !entry.epoch().equals(localEpoch(key))) {
                localCache.asMap().remove(key, entry);
                metrics.localMiss.increment();
                return Lookup.missing();
            }
            metrics.localHit.increment();
            return Lookup.found(entry.value());
        }
        metrics.localHit.increment();
        return Lookup.found(cached);
    }

    private RemoteLookup lookupRemote(Object key) {
        GenerationLookup generation = generation(key);
        if (!generation.available()) {
            return RemoteLookup.missing(generation);
        }
        try {
            ValueWrapper wrapper = remoteCache.get(remoteKey(key, generation.generation()));
            if (wrapper == null) {
                metrics.remoteMiss.increment();
                return RemoteLookup.missing(generation);
            }
            GenerationCheck check = checkGeneration(key, generation);
            if (!check.available() || !check.current()) {
                metrics.remoteMiss.increment();
                return RemoteLookup.missing(generation);
            }
            metrics.remoteHit.increment();
            return RemoteLookup.found(wrapper.get(), generation);
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("L2 cache read failed open, cache={}, reason={}", name, ex.toString());
            return RemoteLookup.missing(GenerationLookup.unavailable());
        }
    }

    private void putRemote(Object key, Object value) {
        try {
            remoteCache.put(key, value);
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("L2 cache write failed open, cache={}, reason={}", name, ex.toString());
        }
    }

    private void putLocal(Object key, Object value) {
        putLocal(key, value, localEpoch(key));
    }

    private void putLocal(Object key, Object value, LocalEpoch epoch) {
        if (localEnabled && value != null) {
            localCache.put(key, new LocalEntry(value, epoch, true, false));
        }
    }

    private void invalidateKey(Object key, boolean publish) {
        GenerationLookup previousGeneration = generation(key);
        long sequence = invalidationSequence.incrementAndGet();
        boolean generationAdvanced = advanceKeyGeneration(key);
        // Agent mutations are rare compared with reads. A process-wide L1 epoch avoids
        // retaining one epoch counter for every key ever invalidated while the L2
        // generation remains key-scoped.
        localClearEpoch.incrementAndGet();
        boolean remoteInvalidated = generationStore.isEnabled()
                || evictRemoteKey(key, previousGeneration);
        boolean published = !publish
                || (generationAdvanced && remoteInvalidated && publishInvalidation(String.valueOf(key)));
        PendingInvalidation pending = new PendingInvalidation(
                sequence,
                key,
                false,
                publish,
                generationAdvanced,
                remoteInvalidated,
                published
        );
        if (pending.complete()) {
            pendingKeyInvalidations.remove(key);
        } else {
            pendingKeyInvalidations.put(key, pending);
        }
    }

    private void invalidateAll(boolean publish) {
        long sequence = invalidationSequence.incrementAndGet();
        boolean generationAdvanced = advanceAllGeneration();
        localClearEpoch.incrementAndGet();
        boolean remoteInvalidated = generationStore.isEnabled() || clearRemote();
        boolean published = !publish
                || (generationAdvanced && remoteInvalidated && publishInvalidation(CLEAR_ALL_MESSAGE));
        PendingInvalidation pending = new PendingInvalidation(
                sequence,
                null,
                true,
                publish,
                generationAdvanced,
                remoteInvalidated,
                published
        );
        if (pending.complete()) {
            pendingClearInvalidation.set(null);
        } else {
            pendingClearInvalidation.set(pending);
        }
    }

    private boolean publishInvalidation(String key) {
        try {
            invalidationBus.publish(name, key);
            return true;
        } catch (RuntimeException ex) {
            metrics.invalidationError.increment();
            log.warn("Cache invalidation publish failed, cache={}, reason={}", name, ex.toString());
            return false;
        }
    }

    private boolean evictRemoteKey(Object key, GenerationLookup previousGeneration) {
        try {
            Object physicalKey = previousGeneration.available()
                    ? remoteKey(key, previousGeneration.generation())
                    : key;
            remoteCache.evict(physicalKey);
            return true;
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("L2 cache eviction failed, cache={}, key={}, reason={}", name, key, ex.toString());
            return false;
        }
    }

    private boolean clearRemote() {
        try {
            remoteCache.clear();
            return true;
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("L2 cache clear failed, cache={}, reason={}", name, ex.toString());
            return false;
        }
    }

    private void retryPendingInvalidations() {
        try {
            PendingInvalidation clear = pendingClearInvalidation.get();
            if (clear != null && retry(clear)) {
                pendingClearInvalidation.compareAndSet(clear, null);
            }
            pendingKeyInvalidations.forEach((key, pending) -> {
                if (retry(pending)) {
                    pendingKeyInvalidations.remove(key, pending);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Cache invalidation compensation failed, cache={}, reason={}", name, ex.toString());
        }
    }

    private boolean retry(PendingInvalidation pending) {
        synchronized (pending) {
            if (!pending.generationAdvanced) {
                pending.generationAdvanced = pending.clear
                        ? advanceAllGeneration()
                        : advanceKeyGeneration(pending.key);
            }
            if (!pending.remoteInvalidated) {
                pending.remoteInvalidated = pending.clear
                        ? clearRemote()
                        : evictRemoteKey(pending.key, generation(pending.key));
            }
            if (!pending.published
                    && pending.publish
                    && pending.generationAdvanced
                    && pending.remoteInvalidated) {
                pending.published = publishInvalidation(
                        pending.clear ? CLEAR_ALL_MESSAGE : String.valueOf(pending.key));
            }
            return pending.complete();
        }
    }

    private void onRemoteInvalidation(String key) {
        localClearEpoch.incrementAndGet();
    }

    private GenerationLookup generation(Object key) {
        if (!generationStore.isEnabled()) {
            return GenerationLookup.available(new CacheGenerationStore.Generation(0L, 0L));
        }
        try {
            return GenerationLookup.available(generationStore.snapshot(name, String.valueOf(key)));
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("Cache generation read failed open, cache={}, reason={}", name, ex.toString());
            return GenerationLookup.unavailable();
        }
    }

    private GenerationCheck checkGeneration(Object key, GenerationLookup expected) {
        if (!expected.available() || !generationStore.isEnabled()) {
            return new GenerationCheck(expected.available(), expected.available());
        }
        try {
            CacheGenerationStore.Generation current = generationStore.snapshot(name, String.valueOf(key));
            return new GenerationCheck(true, current.equals(expected.generation()));
        } catch (RuntimeException ex) {
            metrics.remoteError.increment();
            log.warn("Cache generation verification failed open, cache={}, reason={}", name, ex.toString());
            return new GenerationCheck(false, false);
        }
    }

    private boolean advanceKeyGeneration(Object key) {
        if (!generationStore.isEnabled()) {
            return true;
        }
        try {
            generationStore.advanceKey(name, String.valueOf(key));
            return true;
        } catch (RuntimeException ex) {
            metrics.invalidationError.increment();
            log.warn("Cache generation advance failed, cache={}, key={}, reason={}", name, key, ex.toString());
            return false;
        }
    }

    private boolean advanceAllGeneration() {
        if (!generationStore.isEnabled()) {
            return true;
        }
        try {
            generationStore.advanceAll(name);
            return true;
        } catch (RuntimeException ex) {
            metrics.invalidationError.increment();
            log.warn("Cache global generation advance failed, cache={}, reason={}", name, ex.toString());
            return false;
        }
    }

    private Object remoteKey(Object key, CacheGenerationStore.Generation generation) {
        if (!generationStore.isEnabled()) {
            return key;
        }
        return key + "::g" + generation.global() + ":" + generation.key();
    }

    private LocalEpoch localEpoch(Object key) {
        return new LocalEpoch(localClearEpoch.get());
    }

    private String lockName(Object key) {
        return LOAD_LOCK_PREFIX + name + ":{" + key + "}";
    }

    private void executeAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private Cache unwrapTransactionAwareCache(Cache cache) {
        // TwoLevelCache 已统一在 afterCommit 处理 L1/L2/PubSub。若保留 Spring 的事务装饰器，
        // afterCommit 中的 L2 evict 会再次注册回调，而该回调可能已错过当前提交阶段。
        Cache current = cache;
        while (current instanceof TransactionAwareCacheDecorator transactionAware) {
            current = transactionAware.getTargetCache();
        }
        return current;
    }

    @Override
    public void close() {
        invalidationRetryExecutor.shutdownNow();
        subscription.close();
    }

    @SuppressWarnings("unchecked")
    private <T> T uncheckedCast(Object value) {
        return (T) value;
    }

    private static final class PendingInvalidation {

        private final long sequence;
        private final Object key;
        private final boolean clear;
        private final boolean publish;
        private boolean generationAdvanced;
        private boolean remoteInvalidated;
        private boolean published;

        private PendingInvalidation(
                long sequence,
                Object key,
                boolean clear,
                boolean publish,
                boolean generationAdvanced,
                boolean remoteInvalidated,
                boolean published
        ) {
            this.sequence = sequence;
            this.key = key;
            this.clear = clear;
            this.publish = publish;
            this.generationAdvanced = generationAdvanced;
            this.remoteInvalidated = remoteInvalidated;
            this.published = published;
        }

        private boolean complete() {
            return generationAdvanced && remoteInvalidated && (!publish || published);
        }
    }

    private record Lookup(boolean found, Object value) {

        private static Lookup found(Object value) {
            return new Lookup(true, value);
        }

        private static Lookup missing() {
            return new Lookup(false, null);
        }
    }

    private record RemoteLookup(
            boolean found,
            Object value,
            GenerationLookup generation
    ) {

        private static RemoteLookup found(Object value, GenerationLookup generation) {
            return new RemoteLookup(true, value, generation);
        }

        private static RemoteLookup missing(GenerationLookup generation) {
            return new RemoteLookup(false, null, generation);
        }
    }

    private record LoadResult(Object value, boolean cacheableLocally, boolean retryRequired) {

        private static LoadResult cacheable(Object value) {
            return new LoadResult(value, true, false);
        }
    }

    private record LocalEntry(Object value, LocalEpoch epoch, boolean cacheable, boolean retryRequired) {
    }

    private record LocalEpoch(long clear) {
    }

    private record GenerationLookup(
            boolean available,
            CacheGenerationStore.Generation generation
    ) {

        private static GenerationLookup available(CacheGenerationStore.Generation generation) {
            return new GenerationLookup(true, generation);
        }

        private static GenerationLookup unavailable() {
            return new GenerationLookup(false, null);
        }
    }

    private record GenerationCheck(boolean available, boolean current) {
    }

    private static final class CacheMetrics {

        private final MeterRegistry registry;
        private final Counter localHit;
        private final Counter localMiss;
        private final Counter remoteHit;
        private final Counter remoteMiss;
        private final Counter remoteError;
        private final Counter loadSuccess;
        private final Counter loadFailure;
        private final Counter lockAcquired;
        private final Counter lockTimeout;
        private final Counter lockError;
        private final Counter invalidationError;
        private final Timer loadTimer;

        private CacheMetrics(MeterRegistry registry, String cacheName) {
            this.registry = registry;
            this.localHit = requestCounter(registry, cacheName, "l1", "hit");
            this.localMiss = requestCounter(registry, cacheName, "l1", "miss");
            this.remoteHit = requestCounter(registry, cacheName, "l2", "hit");
            this.remoteMiss = requestCounter(registry, cacheName, "l2", "miss");
            this.remoteError = requestCounter(registry, cacheName, "l2", "error");
            this.loadSuccess = loadCounter(registry, cacheName, "success");
            this.loadFailure = loadCounter(registry, cacheName, "failure");
            this.lockAcquired = lockCounter(registry, cacheName, "acquired");
            this.lockTimeout = lockCounter(registry, cacheName, "timeout");
            this.lockError = lockCounter(registry, cacheName, "error");
            this.invalidationError = Counter.builder("enjoy.cache.invalidation")
                    .tag("cache", cacheName)
                    .tag("outcome", "error")
                    .register(registry);
            this.loadTimer = Timer.builder("enjoy.cache.load")
                    .description("Cache loader execution time")
                    .tag("cache", cacheName)
                    .publishPercentileHistogram()
                    .register(registry);
        }

        private static Counter requestCounter(
                MeterRegistry registry,
                String cacheName,
                String tier,
                String result
        ) {
            return Counter.builder("enjoy.cache.requests")
                    .description("Two-level cache requests")
                    .tag("cache", cacheName)
                    .tag("tier", tier)
                    .tag("result", result)
                    .register(registry);
        }

        private static Counter loadCounter(MeterRegistry registry, String cacheName, String outcome) {
            return Counter.builder("enjoy.cache.loads")
                    .tag("cache", cacheName)
                    .tag("outcome", outcome)
                    .register(registry);
        }

        private static Counter lockCounter(MeterRegistry registry, String cacheName, String outcome) {
            return Counter.builder("enjoy.cache.lock")
                    .tag("cache", cacheName)
                    .tag("outcome", outcome)
                    .register(registry);
        }
    }
}
