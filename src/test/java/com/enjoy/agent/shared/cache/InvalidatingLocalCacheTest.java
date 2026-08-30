package com.enjoy.agent.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class InvalidatingLocalCacheTest {

    @Test
    void invalidationSignalEvictsOtherInstanceWithoutSharingValues() {
        InMemoryBus bus = new InMemoryBus();
        try (InvalidatingLocalCache first = cache(bus);
             InvalidatingLocalCache second = cache(bus)) {
            first.put("7:101", "OWNER");
            second.put("7:101", "MEMBER");

            assertThat(first.get("7:101", String.class)).isEqualTo("OWNER");
            assertThat(second.get("7:101", String.class)).isEqualTo("MEMBER");

            first.evict("7:101");

            assertThat(first.get("7:101")).isNull();
            assertThat(second.get("7:101")).isNull();
        }
    }

    @Test
    void authorizationInvalidationAdvancesTheGlobalEpochWithoutRetainingPrincipalKeys() {
        InMemoryBus bus = new InMemoryBus();
        try (InvalidatingLocalCache cache = cache(bus)) {
            cache.put("7:101", "OWNER");
            cache.put("7:202", "MEMBER");

            cache.evict("7:101");

            assertThat(cache.get("7:101")).isNull();
            assertThat(cache.get("7:202")).isNull();
        }
    }

    @Test
    void loaderIsCollapsedLocally() {
        AtomicInteger loads = new AtomicInteger();
        try (InvalidatingLocalCache cache = cache(new InMemoryBus())) {
            assertThat(cache.get("7:101", () -> {
                loads.incrementAndGet();
                return "OWNER";
            })).isEqualTo("OWNER");
            assertThat(cache.get("7:101", () -> {
                loads.incrementAndGet();
                return "MEMBER";
            })).isEqualTo("OWNER");
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void invalidationWinsAgainstAnInFlightAuthorizationLoad() throws Exception {
        AtomicReference<String> source = new AtomicReference<>("OWNER");
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        try (InvalidatingLocalCache cache = cache(new InMemoryBus());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldLoad = executor.submit(() -> cache.get("7:101", () -> {
                String snapshot = source.get();
                loaderEntered.countDown();
                releaseLoader.await();
                return snapshot;
            }));
            assertThat(loaderEntered.await(2, TimeUnit.SECONDS)).isTrue();

            source.set("MEMBER");
            cache.evict("7:101");
            releaseLoader.countDown();

            assertThat(oldLoad.get(2, TimeUnit.SECONDS)).isEqualTo("MEMBER");
            assertThat(cache.get("7:101", source::get)).isEqualTo("MEMBER");
        }
    }

    private InvalidatingLocalCache cache(CacheInvalidationBus bus) {
        return new InvalidatingLocalCache(
                "auth-context-v1",
                Caffeine.newBuilder().maximumSize(100).build(),
                bus
        );
    }

    private static final class InMemoryBus implements CacheInvalidationBus {
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String cacheName, String key) {
            listeners.forEach(listener -> listener.accept(key));
        }

        @Override
        public Subscription subscribe(String cacheName, Consumer<String> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }
}
