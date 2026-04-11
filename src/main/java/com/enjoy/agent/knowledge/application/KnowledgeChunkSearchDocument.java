package com.enjoy.agent.knowledge.application;

import java.time.Instant;

/**
 * 写入 Elasticsearch 的知识切片文档。
 */
public record KnowledgeChunkSearchDocument(
        Long chunkId,
        Long tenantId,
        Long knowledgeBaseId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        float[] contentVector,
        Instant createdAt,
        Instant updatedAt
) {
}
