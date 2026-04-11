package com.enjoy.agent.knowledge.application;

/**
 * 知识检索结果。
 */
public record KnowledgeRetrievalResult(
        String retrievalContext,
        KnowledgeRetrievalDebug debug
) {
}
