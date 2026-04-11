package com.enjoy.agent.knowledge.application;

import java.util.List;

/**
 * 一次知识检索的调试信息。
 */
public record KnowledgeRetrievalDebug(
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String originalQuery,
        String retrievalQuery,
        boolean rewriteApplied,
        Integer recallTopK,
        Integer finalTopK,
        boolean rerankApplied,
        String rerankModel,
        List<KnowledgeRetrievalHit> hits
) {
}
