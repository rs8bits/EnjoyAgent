package com.enjoy.agent.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionSynchronization;

class TwoLevelCacheTest {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void get_shouldBackfillLocalCacheAfterRemoteHit() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        TwoLevelCache cache = cache("agent-config-v4", remote, new InMemoryInvalidationBus(), false, null);
        remote.put("7:42", "remote-value");

        assertThat(cache.get("7:42", String.class)).isEqualTo("remote-value");
        remote.evict("7:42");

        assertThat(cache.get("7:42", String.class)).isEqualTo("remote-value");
    }

    @Test
    void getWithLoader_shouldCollapseConcurrentLoadsWithinOneInstance() throws Exception {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        TwoLevelCache cache = cache("agent-config-v4", remote, new InMemoryInvalidationBus(), false, null);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> futures = IntStream.range(0, 32)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return cache.get("7:42", () -> {
                            loads.incrementAndGet();
                            loaderEntered.countDown();
                            releaseLoader.await();
                            return "loaded";
                        });
                    }))
                    .toList();

            start.countDown();
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();

            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("loaded");
            }
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void distributedGuard_shouldCollapseLoadsAcrossTwoInstances() throws Exception {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        InMemoryInvalidationBus bus = new InMemoryInvalidationBus();
        BlockingLoadGuard guard = new BlockingLoadGuard();
        TwoLevelCache first = cache("agent-config-v4", remote, bus, true, guard);
        TwoLevelCache second = cache("agent-config-v4", remote, bus, true, guard);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            return "shared";
        };
        try {
            Future<String> firstResult = executor.submit(() -> {
                start.await();
                return first.get("7:42", loader);
            });
            Future<String> secondResult = executor.submit(() -> {
                start.await();
                return second.get("7:42", loader);
            });

            start.countDown();
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo("shared");
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo("shared");
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eviction_shouldWinAgainstConcurrentCacheFill() throws Exception {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        BlockingLoadGuard guard = new BlockingLoadGuard();
        InMemoryGenerationStore generations = new InMemoryGenerationStore();
        TwoLevelCache cache = cache(
                "agent-config-v4",
                remote,
                new InMemoryInvalidationBus(),
                true,
                guard,
                generations
        );
        AtomicReference<String> source = new AtomicReference<>("old");
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> load = executor.submit(() -> cache.get("7:42", () -> {
                String snapshot = source.get();
                loaderEntered.countDown();
                releaseLoader.await();
                return snapshot;
            }));
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            source.set("new");
            Future<?> eviction = executor.submit(() -> cache.evict("7:42"));

            // generation 推进不再等待旧 loader 持有的回源锁。
            eviction.get(5, TimeUnit.SECONDS);
            releaseLoader.countDown();
            assertThat(load.get(5, TimeUnit.SECONDS)).isEqualTo("new");

            assertThat(cache.get("7:42", String.class)).isEqualTo("new");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void generationFence_shouldRejectLateFillWithoutDistributedLock() throws Exception {
        ConcurrentMapCache remote = new ConcurrentMapCache("auth-context-v1");
        InMemoryInvalidationBus bus = new InMemoryInvalidationBus();
        InMemoryGenerationStore generations = new InMemoryGenerationStore();
        TwoLevelCache first = cache("auth-context-v1", remote, bus, false, null, generations);
        TwoLevelCache second = cache("auth-context-v1", remote, bus, false, null, generations);
        AtomicReference<String> source = new AtomicReference<>("old-role");
        CountDownLatch oldLoaderEntered = new CountDownLatch(1);
        CountDownLatch releaseOldLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> oldRequest = executor.submit(() -> first.get("7:101", () -> {
                String snapshot = source.get();
                oldLoaderEntered.countDown();
                releaseOldLoader.await();
                return snapshot;
            }));
            assertThat(oldLoaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            source.set("new-role");
            Future<?> eviction = executor.submit(() -> second.evict("7:101"));
            // 失效只推进 fencing generation / 本地 epoch，不能等待 in-flight Caffeine loader。
            eviction.get(1, TimeUnit.SECONDS);
            Future<String> requestAfterEviction = executor.submit(
                    () -> first.get("7:101", source::get)
            );

            releaseOldLoader.countDown();
            assertThat(oldRequest.get(5, TimeUnit.SECONDS)).isEqualTo("new-role");
            assertThat(requestAfterEviction.get(5, TimeUnit.SECONDS)).isEqualTo("new-role");
            assertThat(first.get("7:101", () -> "unexpected")).isEqualTo("new-role");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unavailableGenerationStore_shouldReturnLoaderValueWithoutCachingIt() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        CacheGenerationStore unavailable = new CacheGenerationStore() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public Generation snapshot(String cacheName, String key) {
                throw new IllegalStateException("redis unavailable");
            }

            @Override
            public Generation advanceKey(String cacheName, String key) {
                throw new IllegalStateException("redis unavailable");
            }

            @Override
            public long advanceAll(String cacheName) {
                throw new IllegalStateException("redis unavailable");
            }
        };
        TwoLevelCache cache = cache(
                "agent-config-v4",
                remote,
                new InMemoryInvalidationBus(),
                false,
                null,
                unavailable
        );
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("7:42", () -> "value-" + loads.incrementAndGet())).isEqualTo("value-1");
        assertThat(cache.get("7:42", () -> "value-" + loads.incrementAndGet())).isEqualTo("value-2");
        assertThat(remote.get("7:42")).isNull();
    }

    @Test
    void failedInvalidation_shouldAdvanceGenerationAfterRedisRecovers() throws Exception {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        RecoveringGenerationStore generations = new RecoveringGenerationStore();
        TwoLevelCache cache = cache(
                "agent-config-v4",
                remote,
                new InMemoryInvalidationBus(),
                false,
                null,
                generations
        );
        try {
            cache.put("7:42", "old");
            assertThat(cache.get("7:42", String.class)).isEqualTo("old");

            generations.available.set(false);
            cache.evict("7:42");
            generations.available.set(true);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (generations.snapshot("agent-config-v4", "7:42").key() == 0L
                    && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }

            assertThat(generations.snapshot("agent-config-v4", "7:42").key()).isPositive();
            assertThat(cache.get("7:42", () -> "new")).isEqualTo("new");
            assertThat(cache.get("7:42", String.class)).isEqualTo("new");
            assertThat(remote.get("7:42::g0:0", String.class)).isEqualTo("old");
        } finally {
            cache.close();
        }
    }

    @Test
    void remoteFailureWithLocalDisabled_shouldFailOpenToLoaderWithoutRetainingValue() {
        Cache remote = mock(Cache.class);
        when(remote.get("7:8")).thenThrow(new IllegalStateException("redis unavailable"));
        TwoLevelCache cache = new TwoLevelCache(
                "session-summary-v3",
                remote,
                Caffeine.newBuilder().maximumSize(1).build(),
                false,
                false,
                null,
                CacheGenerationStore.disabled(),
                new InMemoryInvalidationBus(),
                new SimpleMeterRegistry()
        );
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("7:8", () -> "summary-" + loads.incrementAndGet())).isEqualTo("summary-1");
        assertThat(cache.get("7:8", () -> "summary-" + loads.incrementAndGet())).isEqualTo("summary-2");
    }

    @Test
    void evictionMessage_shouldInvalidateAnotherInstancesLocalCache() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        InMemoryInvalidationBus bus = new InMemoryInvalidationBus();
        TwoLevelCache first = cache("agent-config-v4", remote, bus, false, null);
        TwoLevelCache second = cache("agent-config-v4", remote, bus, false, null);
        remote.put("7:42", "old");
        assertThat(first.get("7:42", String.class)).isEqualTo("old");
        assertThat(second.get("7:42", String.class)).isEqualTo("old");

        first.evict("7:42");
        remote.put("7:42", "new");

        assertThat(second.get("7:42", String.class)).isEqualTo("new");
    }

    @Test
    void eviction_shouldWaitForTransactionCommit() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        TwoLevelCache cache = cache("agent-config-v4", remote, new InMemoryInvalidationBus(), false, null);
        remote.put("7:42", "old");
        assertThat(cache.get("7:42", String.class)).isEqualTo("old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("7:42");
        assertThat(remote.get("7:42", String.class)).isEqualTo("old");

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(remote.get("7:42")).isNull();
        assertThat(cache.get("7:42")).isNull();
    }

    @Test
    void eviction_shouldNotRunAfterTransactionRollback() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        TwoLevelCache cache = cache("agent-config-v4", remote, new InMemoryInvalidationBus(), false, null);
        remote.put("7:42", "old");
        assertThat(cache.get("7:42", String.class)).isEqualTo("old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("7:42");
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(remote.get("7:42", String.class)).isEqualTo("old");
        assertThat(cache.get("7:42", String.class)).isEqualTo("old");
    }

    @Test
    void eviction_shouldNotBeDeferredTwiceByTransactionAwareRemoteDecorator() {
        ConcurrentMapCache remoteTarget = new ConcurrentMapCache("agent-config-v4");
        TransactionAwareCacheDecorator decoratedRemote = new TransactionAwareCacheDecorator(remoteTarget);
        TwoLevelCache cache = cache(
                "agent-config-v4",
                decoratedRemote,
                new InMemoryInvalidationBus(),
                false,
                null
        );
        remoteTarget.put("7:42", "old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("7:42");
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(remoteTarget.get("7:42")).isNull();
    }

    @Test
    void remoteFailure_shouldFailOpenToLoaderAndRetainLocalHit() {
        Cache remote = mock(Cache.class);
        when(remote.get("7:42")).thenThrow(new IllegalStateException("redis unavailable"));
        TwoLevelCache cache = cache("agent-config-v4", remote, new InMemoryInvalidationBus(), false, null);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("7:42", () -> {
            loads.incrementAndGet();
            return "fallback";
        })).isEqualTo("fallback");
        assertThat(cache.get("7:42", () -> {
            loads.incrementAndGet();
            return "unexpected";
        })).isEqualTo("fallback");
        assertThat(loads).hasValue(1);
    }

    @Test
    void loaderRuntimeException_shouldKeepOriginalBusinessSemantics() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        TwoLevelCache cache = cache(
                "agent-config-v4",
                remote,
                new InMemoryInvalidationBus(),
                true,
                new BlockingLoadGuard()
        );
        IllegalArgumentException businessException = new IllegalArgumentException("not found");

        assertThatThrownBy(() -> cache.get("7:404", () -> {
            throw businessException;
        })).isSameAs(businessException);
    }

    @Test
    void unavailableDistributedGuard_shouldFailOpenToLoader() {
        ConcurrentMapCache remote = new ConcurrentMapCache("agent-config-v4");
        CacheLoadGuard unavailableGuard = new CacheLoadGuard() {
            @Override
            public <T> GuardResult<T> tryExecute(String lockName, Callable<T> action) {
                throw new CacheLoadGuardUnavailableException("redis unavailable", new IllegalStateException());
            }
        };
        TwoLevelCache cache = cache(
                "agent-config-v4",
                remote,
                new InMemoryInvalidationBus(),
                true,
                unavailableGuard
        );

        assertThat(cache.get("7:42", () -> "database-fallback")).isEqualTo("database-fallback");
    }

    private TwoLevelCache cache(
            String name,
            Cache remote,
            CacheInvalidationBus bus,
            boolean distributedLockEnabled,
            CacheLoadGuard guard
    ) {
        return new TwoLevelCache(
                name,
                remote,
                Caffeine.newBuilder().maximumSize(100).recordStats().build(),
                true,
                distributedLockEnabled,
                guard,
                bus,
                new SimpleMeterRegistry()
        );
    }

    private TwoLevelCache cache(
            String name,
            Cache remote,
            CacheInvalidationBus bus,
            boolean distributedLockEnabled,
            CacheLoadGuard guard,
            CacheGenerationStore generationStore
    ) {
        return new TwoLevelCache(
                name,
                remote,
                Caffeine.newBuilder().maximumSize(100).recordStats().build(),
                true,
                distributedLockEnabled,
                guard,
                generationStore,
                bus,
                new SimpleMeterRegistry()
        );
    }

    private static final class BlockingLoadGuard implements CacheLoadGuard {

        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public <T> GuardResult<T> tryExecute(String lockName, Callable<T> action) throws Exception {
            ReentrantLock lock = locks.computeIfAbsent(lockName, ignored -> new ReentrantLock());
            lock.lock();
            try {
                return GuardResult.acquired(action.call());
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class InMemoryGenerationStore implements CacheGenerationStore {

        private final AtomicLong global = new AtomicLong();
        private final Map<String, AtomicLong> keys = new ConcurrentHashMap<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Generation snapshot(String cacheName, String key) {
            return new Generation(global.get(), key(cacheName, key).get());
        }

        @Override
        public Generation advanceKey(String cacheName, String key) {
            return new Generation(global.get(), key(cacheName, key).incrementAndGet());
        }

        @Override
        public long advanceAll(String cacheName) {
            return global.incrementAndGet();
        }

        private AtomicLong key(String cacheName, String key) {
            return keys.computeIfAbsent(cacheName + ":" + key, ignored -> new AtomicLong());
        }
    }

    private static final class RecoveringGenerationStore implements CacheGenerationStore {

        private final AtomicReference<Boolean> available = new AtomicReference<>(true);
        private final InMemoryGenerationStore delegate = new InMemoryGenerationStore();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Generation snapshot(String cacheName, String key) {
            requireAvailable();
            return delegate.snapshot(cacheName, key);
        }

        @Override
        public Generation advanceKey(String cacheName, String key) {
            requireAvailable();
            return delegate.advanceKey(cacheName, key);
        }

        @Override
        public long advanceAll(String cacheName) {
            requireAvailable();
            return delegate.advanceAll(cacheName);
        }

        private void requireAvailable() {
            if (!available.get()) {
                throw new IllegalStateException("redis unavailable");
            }
        }
    }

    private static final class InMemoryInvalidationBus implements CacheInvalidationBus {

        private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

        @Override
        public void publish(String cacheName, String key) {
            listeners.getOrDefault(cacheName, List.of()).forEach(listener -> listener.accept(key));
        }

        @Override
        public Subscription subscribe(String cacheName, Consumer<String> listener) {
            listeners.computeIfAbsent(cacheName, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(listener);
            return () -> listeners.getOrDefault(cacheName, List.of()).remove(listener);
        }
    }
}
