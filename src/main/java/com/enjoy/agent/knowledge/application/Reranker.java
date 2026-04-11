package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import java.util.List;

/**
 * 二阶段重排器，负责把召回候选重新排序。
 */
public interface Reranker {

    /**
     * 对候选知识片段进行重排。
     */
    List<KnowledgeRetrievalHit> rerank(
            PreparedModelConfig authModelConfig,
            String query,
            List<RetrievedKnowledgeChunk> candidates,
            int finalTopK
    );
}
