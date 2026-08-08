package com.enjoy.agent.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonCacheLoadGuardTest {

    @Test
    void tryExecute_shouldRunActionAndReleaseOwnedLock() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("lock-key")).thenReturn(lock);
        when(lock.tryLock(250L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonCacheLoadGuard guard = new RedissonCacheLoadGuard(client, Duration.ofMillis(250));

        CacheLoadGuard.GuardResult<String> result = guard.tryExecute("lock-key", () -> "loaded");

        assertThat(result.acquired()).isTrue();
        assertThat(result.value()).isEqualTo("loaded");
        verify(lock).unlock();
    }

    @Test
    void tryExecute_shouldNotRunActionWhenWaitTimesOut() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        @SuppressWarnings("unchecked")
        Callable<String> action = mock(Callable.class);
        when(client.getLock("lock-key")).thenReturn(lock);
        when(lock.tryLock(250L, TimeUnit.MILLISECONDS)).thenReturn(false);
        RedissonCacheLoadGuard guard = new RedissonCacheLoadGuard(client, Duration.ofMillis(250));

        CacheLoadGuard.GuardResult<String> result = guard.tryExecute("lock-key", action);

        assertThat(result.acquired()).isFalse();
        verify(action, never()).call();
        verify(lock, never()).unlock();
    }
}
