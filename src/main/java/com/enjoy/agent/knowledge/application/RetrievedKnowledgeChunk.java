package com.enjoy.agent.knowledge.application;

/**
 * 混合召回命中的知识片段。
 */
public record RetrievedKnowledgeChunk(
        Long id,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        Double fusionScore,
        Double denseScore,
        Integer denseRank,
        Double lexicalScore,
        Integer lexicalRank,
        String matchedBy
) {
}
