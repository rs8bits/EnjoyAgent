package com.enjoy.agent.shared.cache;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

/**
 * 使用 Redis 原子计数器保存缓存代次。
 *
 * <p>代次 key 故意不设置过期时间，避免计数器归零后旧物理缓存 key 再次可见（ABA）。</p>
 */
public final class RedissonCacheGenerationStore implements CacheGenerationStore {

    private static final String PREFIX = "enjoy:cache-generation:";

    private final RedissonClient redissonClient;

    public RedissonCacheGenerationStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Generation snapshot(String cacheName, String key) {
        long globalGeneration = current(redissonClient.getAtomicLong(globalName(cacheName)));
        long keyGeneration = current(redissonClient.getAtomicLong(keyName(cacheName, key)));
        return new Generation(globalGeneration, keyGeneration);
    }

    @Override
    public Generation advanceKey(String cacheName, String key) {
        long globalGeneration = current(redissonClient.getAtomicLong(globalName(cacheName)));
        RAtomicLong keyCounter = redissonClient.getAtomicLong(keyName(cacheName, key));
        current(keyCounter);
        long keyGeneration = keyCounter.incrementAndGet();
        return new Generation(globalGeneration, keyGeneration);
    }

    @Override
    public long advanceAll(String cacheName) {
        RAtomicLong globalCounter = redissonClient.getAtomicLong(globalName(cacheName));
        current(globalCounter);
        return globalCounter.incrementAndGet();
    }

    private long current(RAtomicLong counter) {
        long current = counter.get();
        if (current != 0L) {
            return current;
        }
        // generation key 若被 Redis 淘汰，不得回到固定的 0，否则仍在 TTL 内的旧数据 key
        // 可能发生 ABA 再次可见。随机非零种子把碰撞概率降到工程上可忽略。
        long seed = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE / 2L);
        if (counter.compareAndSet(0L, seed)) {
            return seed;
        }
        return counter.get();
    }

    private String globalName(String cacheName) {
        return PREFIX + cacheName + ":all";
    }

    private String keyName(String cacheName, String key) {
        return PREFIX + cacheName + ":{" + key + "}";
    }
}
