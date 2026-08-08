package com.enjoy.agent.shared.cache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 基于 Redis Pub/Sub 的跨实例本地缓存失效总线。
 */
public final class RedissonCacheInvalidationBus implements CacheInvalidationBus, AutoCloseable {

    private static final String TOPIC_PREFIX = "enjoy:cache-invalidation:";

    private final RedissonClient redissonClient;
    private final Map<String, List<Consumer<String>>> localListeners = new ConcurrentHashMap<>();
    private final Map<String, RemoteSubscription> remoteSubscriptions = new ConcurrentHashMap<>();

    public RedissonCacheInvalidationBus(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
    }

    @Override
    public void publish(String cacheName, String key) {
        topic(cacheName).publish(Objects.requireNonNull(key, "key"));
    }

    @Override
    public Subscription subscribe(String cacheName, Consumer<String> listener) {
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(listener, "listener");
        localListeners.computeIfAbsent(cacheName, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        remoteSubscriptions.computeIfAbsent(cacheName, this::subscribeRemote);
        return () -> {
            List<Consumer<String>> listeners = localListeners.get(cacheName);
            if (listeners != null) {
                listeners.remove(listener);
            }
        };
    }

    private RemoteSubscription subscribeRemote(String cacheName) {
        RTopic topic = topic(cacheName);
        int listenerId = topic.addListener(String.class, (channel, key) -> {
            List<Consumer<String>> listeners = localListeners.get(cacheName);
            if (listeners != null) {
                listeners.forEach(listener -> listener.accept(key));
            }
        });
        return new RemoteSubscription(topic, listenerId);
    }

    private RTopic topic(String cacheName) {
        return redissonClient.getTopic(TOPIC_PREFIX + cacheName, StringCodec.INSTANCE);
    }

    @Override
    public void close() {
        remoteSubscriptions.values().forEach(subscription ->
                subscription.topic().removeListener(subscription.listenerId()));
        remoteSubscriptions.clear();
        localListeners.clear();
    }

    private record RemoteSubscription(RTopic topic, int listenerId) {
    }
}
