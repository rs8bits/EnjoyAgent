package com.enjoy.agent.support.application;

/**
 * 供上下文组装器读取的不可变摘要快照。
 *
 * <p>没有摘要时使用 {@link #empty()}，避免调用方把“无摘要”和数据库异常混在
 * 一起处理。</p>
 */
public record SupportMemorySnapshot(String summary, long coveredThroughSequence) {

    public static SupportMemorySnapshot empty() {
        return new SupportMemorySnapshot(null, 0L);
    }
}
