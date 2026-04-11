package com.enjoy.agent.knowledge.application;

/**
 * 待写入数据库的知识切片行数据。
 */
public record KnowledgeChunkRow(
        int chunkIndex,
        String content,
        float[] embedding
) {
}
