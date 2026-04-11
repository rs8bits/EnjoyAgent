package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.modelgateway.application.PreparedModelConfig;

/**
 * 供知识库检索使用的运行时知识库快照。
 */
public record PreparedKnowledgeBase(
        Long knowledgeBaseId,
        String knowledgeBaseName,
        PreparedModelConfig embeddingModelConfig,
        boolean rerankEnabled,
        PreparedModelConfig rerankModelConfig
) {
}
