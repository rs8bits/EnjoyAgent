package com.enjoy.agent.shared.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 Redisson watchdog 锁限制多实例对同一热点 key 的并发回源。
 */
public final class RedissonCacheLoadGuard implements CacheLoadGuard {

    private static final Logger log = LoggerFactory.getLogger(RedissonCacheLoadGuard.class);

    private final RedissonClient redissonClient;
    private final Duration waitTime;

    public RedissonCacheLoadGuard(RedissonClient redissonClient, Duration waitTime) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.waitTime = Objects.requireNonNull(waitTime, "waitTime");
        if (waitTime.isNegative()) {
            throw new IllegalArgumentException("waitTime must not be negative");
        }
    }

    @Override
    public <T> GuardResult<T> tryExecute(String lockName, Callable<T> action) throws Exception {
        RLock lock;
        boolean acquired;
        try {
            lock = redissonClient.getLock(lockName);
            // 不传 leaseTime，由 Redisson watchdog 在回源期间续期。
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (RuntimeException ex) {
            throw new CacheLoadGuardUnavailableException("Distributed cache load guard is unavailable", ex);
        }
        if (!acquired) {
            return GuardResult.notAcquired();
        }

        try {
            return GuardResult.acquired(action.call());
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to release cache load lock, lockName={}, reason={}", lockName, ex.toString());
            }
        }
    }
}
