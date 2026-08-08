package com.enjoy.agent.shared.cache;

/**
 * 为两级缓存提供单调递增的逻辑代次。
 *
 * <p>L2 的物理 key 会携带当前代次。失效操作先推进代次，因此在失效之前开始的慢回源
 * 即使稍后写入 Redis，也只会写入已经不可达的旧代次 key。</p>
 */
public interface CacheGenerationStore {

    boolean isEnabled();

    Generation snapshot(String cacheName, String key);

    Generation advanceKey(String cacheName, String key);

    long advanceAll(String cacheName);

    record Generation(long global, long key) {
    }

    static CacheGenerationStore disabled() {
        return new CacheGenerationStore() {
            private static final Generation ZERO = new Generation(0L, 0L);

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public Generation snapshot(String cacheName, String key) {
                return ZERO;
            }

            @Override
            public Generation advanceKey(String cacheName, String key) {
                return ZERO;
            }

            @Override
            public long advanceAll(String cacheName) {
                return 0L;
            }
        };
    }
}
