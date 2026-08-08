package com.enjoy.agent.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

class RedissonCacheGenerationStoreTest {

    private static final String CACHE_NAME = "agent-config-v4";
    private static final String LOGICAL_KEY = "7:42";
    private static final String GLOBAL_COUNTER = "enjoy:cache-generation:agent-config-v4:all";
    private static final String KEY_COUNTER = "enjoy:cache-generation:agent-config-v4:{7:42}";

    @Test
    void snapshot_shouldReadGlobalAndPerKeyGeneration() {
        RedissonClient client = mock(RedissonClient.class);
        RAtomicLong global = atomicLong(client, GLOBAL_COUNTER, 41L);
        RAtomicLong key = atomicLong(client, KEY_COUNTER, 9L);
        RedissonCacheGenerationStore store = new RedissonCacheGenerationStore(client);

        CacheGenerationStore.Generation generation = store.snapshot(CACHE_NAME, LOGICAL_KEY);

        assertThat(generation).isEqualTo(new CacheGenerationStore.Generation(41L, 9L));
        verify(global).get();
        verify(key).get();
    }

    @Test
    void advanceKey_shouldIncrementOnlyPerKeyGeneration() {
        RedissonClient client = mock(RedissonClient.class);
        RAtomicLong global = atomicLong(client, GLOBAL_COUNTER, 41L);
        RAtomicLong key = atomicLong(client, KEY_COUNTER, 9L);
        when(key.incrementAndGet()).thenReturn(10L);
        RedissonCacheGenerationStore store = new RedissonCacheGenerationStore(client);

        CacheGenerationStore.Generation generation = store.advanceKey(CACHE_NAME, LOGICAL_KEY);

        assertThat(generation).isEqualTo(new CacheGenerationStore.Generation(41L, 10L));
        verify(global).get();
        verify(key).get();
        verify(key).incrementAndGet();
    }

    @Test
    void advanceAll_shouldIncrementGlobalGeneration() {
        RedissonClient client = mock(RedissonClient.class);
        RAtomicLong global = atomicLong(client, GLOBAL_COUNTER, 41L);
        when(global.incrementAndGet()).thenReturn(42L);
        RedissonCacheGenerationStore store = new RedissonCacheGenerationStore(client);

        assertThat(store.advanceAll(CACHE_NAME)).isEqualTo(42L);
        verify(global).get();
        verify(global).incrementAndGet();
    }

    private RAtomicLong atomicLong(RedissonClient client, String name, long value) {
        RAtomicLong counter = mock(RAtomicLong.class);
        when(client.getAtomicLong(name)).thenReturn(counter);
        when(counter.get()).thenReturn(value);
        return counter;
    }
}
