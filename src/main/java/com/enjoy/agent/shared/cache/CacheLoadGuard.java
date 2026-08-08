package com.enjoy.agent.shared.cache;

import java.util.concurrent.Callable;

/**
 * 可选的跨实例缓存回源保护。
 */
public interface CacheLoadGuard {

    <T> GuardResult<T> tryExecute(String lockName, Callable<T> action) throws Exception;

    record GuardResult<T>(boolean acquired, T value) {

        public static <T> GuardResult<T> acquired(T value) {
            return new GuardResult<>(true, value);
        }

        public static <T> GuardResult<T> notAcquired() {
            return new GuardResult<>(false, null);
        }
    }
}
