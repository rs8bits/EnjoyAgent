package com.enjoy.agent.knowledge.application;

/**
 * 一次知识检索中的单条召回/重排结果。
 */
public record KnowledgeRetrievalHit(
        Long chunkId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        Double recallScore,
        Integer recallRank,
        Double denseScore,
        Integer denseRank,
        Double lexicalScore,
        Integer lexicalRank,
        String matchedBy,
        Double rerankScore,
        Integer rerankRank,
        boolean selected
) {
}
