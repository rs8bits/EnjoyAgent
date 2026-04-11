package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 知识库向量检索服务。
 */
@Service
public class KnowledgeRetrievalService {

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeProperties knowledgeProperties;
    private final QueryRewriteService queryRewriteService;
    private final Retriever retriever;
    private final Reranker reranker;

    public KnowledgeRetrievalService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            KnowledgeProperties knowledgeProperties,
            QueryRewriteService queryRewriteService,
            Retriever retriever,
            Reranker reranker
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.knowledgeProperties = knowledgeProperties;
        this.queryRewriteService = queryRewriteService;
        this.retriever = retriever;
        this.reranker = reranker;
    }

    /**
     * 按问题内容检索相关知识，并拼装成本次聊天要用的上下文。
     */
    public String buildRetrievalContext(PreparedKnowledgeBase knowledgeBase, String question) {
        return retrieve(knowledgeBase, null, null, List.of(), question).retrievalContext();
    }

    /**
     * 执行一次带调试信息的知识检索。
     */
    public KnowledgeRetrievalResult retrieve(
            PreparedKnowledgeBase knowledgeBase,
            PreparedModelConfig chatModelConfig,
            String sessionMemorySummary,
            List<ChatPromptMessage> historyMessages,
            String question
    ) {
        if (knowledgeBase == null || question == null || question.isBlank()) {
            return new KnowledgeRetrievalResult(null, null);
        }
        if (!knowledgeChunkRepository.existsByKnowledgeBase_Id(knowledgeBase.knowledgeBaseId())) {
            return new KnowledgeRetrievalResult(null, null);
        }

        QueryRewriteResult rewriteResult = queryRewriteService.rewrite(
                chatModelConfig,
                sessionMemorySummary,
                historyMessages,
                question
        );
        List<RetrievedKnowledgeChunk> candidates = retriever.retrieve(
                knowledgeBase,
                rewriteResult.rewrittenQuery(),
                Math.max(knowledgeProperties.getRecallTopK(), knowledgeProperties.getRetrievalTopK())
        );
        List<KnowledgeRetrievalHit> hits = reranker.rerank(
                knowledgeBase.rerankEnabled() ? knowledgeBase.rerankModelConfig() : null,
                rewriteResult.rewrittenQuery(),
                candidates,
                knowledgeProperties.getRetrievalTopK()
        );

        return new KnowledgeRetrievalResult(
                buildRetrievalContext(knowledgeBase, hits),
                buildDebug(knowledgeBase, rewriteResult, hits)
        );
    }

    private String buildRetrievalContext(PreparedKnowledgeBase knowledgeBase, List<KnowledgeRetrievalHit> hits) {
        List<KnowledgeRetrievalHit> selectedHits = hits == null
                ? List.of()
                : hits.stream()
                .filter(KnowledgeRetrievalHit::selected)
                .sorted((left, right) -> compareRank(left.rerankRank(), right.rerankRank(), left.recallRank(), right.recallRank()))
                .toList();
        if (selectedHits.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("以下是从知识库“").append(knowledgeBase.knowledgeBaseName()).append("”检索到的相关内容：\n\n");
        for (int i = 0; i < selectedHits.size(); i++) {
            KnowledgeRetrievalHit chunk = selectedHits.get(i);
            builder.append("[片段").append(i + 1).append("]");
            if (chunk.documentName() != null && !chunk.documentName().isBlank()) {
                builder.append("（").append(chunk.documentName()).append(" #").append(chunk.chunkIndex()).append("）");
            }
            builder.append("\n");
            builder.append(chunk.content()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private KnowledgeRetrievalDebug buildDebug(
            PreparedKnowledgeBase knowledgeBase,
            QueryRewriteResult rewriteResult,
            List<KnowledgeRetrievalHit> hits
    ) {
        if (!knowledgeProperties.isRetrievalDebugEnabled()) {
            return null;
        }
        return new KnowledgeRetrievalDebug(
                knowledgeBase.knowledgeBaseId(),
                knowledgeBase.knowledgeBaseName(),
                rewriteResult.originalQuery(),
                rewriteResult.rewrittenQuery(),
                rewriteResult.rewriteApplied(),
                hits == null ? 0 : hits.size(),
                hits == null ? 0 : (int) hits.stream().filter(KnowledgeRetrievalHit::selected).count(),
                hits != null && hits.stream().anyMatch(hit -> hit.rerankRank() != null),
                knowledgeBase.rerankEnabled() && knowledgeBase.rerankModelConfig() != null
                        ? knowledgeBase.rerankModelConfig().modelName()
                        : null,
                hits == null ? List.of() : List.copyOf(hits)
        );
    }

    private int compareRank(Integer leftRerankRank, Integer rightRerankRank, Integer leftRecallRank, Integer rightRecallRank) {
        if (leftRerankRank != null && rightRerankRank != null) {
            return Integer.compare(leftRerankRank, rightRerankRank);
        }
        if (leftRerankRank != null) {
            return -1;
        }
        if (rightRerankRank != null) {
            return 1;
        }
        return Integer.compare(
                leftRecallRank == null ? Integer.MAX_VALUE : leftRecallRank,
                rightRecallRank == null ? Integer.MAX_VALUE : rightRecallRank
        );
    }
}
