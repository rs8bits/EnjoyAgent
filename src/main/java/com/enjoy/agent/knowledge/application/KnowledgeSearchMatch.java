package com.enjoy.agent.knowledge.application;

/**
 * Elasticsearch 单路召回结果。
 */
public record KnowledgeSearchMatch(
        Long chunkId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        Double score,
        Integer rank
) {
}
