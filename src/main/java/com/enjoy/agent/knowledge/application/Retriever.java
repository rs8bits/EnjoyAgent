package com.enjoy.agent.knowledge.application;

import java.util.List;

/**
 * 知识检索器，负责召回候选知识片段。
 */
public interface Retriever {

    /**
     * 按查询内容召回候选知识片段。
     */
    List<RetrievedKnowledgeChunk> retrieve(PreparedKnowledgeBase knowledgeBase, String query, int topK);
}
