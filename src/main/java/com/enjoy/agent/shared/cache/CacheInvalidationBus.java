package com.enjoy.agent.shared.cache;

import java.util.function.Consumer;

/**
 * 在应用实例之间传播 L1 失效事件。L2 Redis 由写入方直接删除。
 */
public interface CacheInvalidationBus {

    void publish(String cacheName, String key);

    Subscription subscribe(String cacheName, Consumer<String> listener);

    @FunctionalInterface
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }

    static CacheInvalidationBus noOp() {
        return new CacheInvalidationBus() {
            @Override
            public void publish(String cacheName, String key) {
                // Intentionally empty.
            }

            @Override
            public Subscription subscribe(String cacheName, Consumer<String> listener) {
                return () -> { };
            }
        };
    }
}
