package com.enjoy.agent.support.application;

/** 写入 support_turn 的非敏感模型调用元数据。 */
public record SupportModelUsage(
        String provider,
        String modelName,
        Long officialModelConfigId,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
