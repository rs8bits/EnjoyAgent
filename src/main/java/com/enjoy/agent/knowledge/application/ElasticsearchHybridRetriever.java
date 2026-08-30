package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 基于 Elasticsearch 的混合检索器，支持 dense + lexical + RRF 融合。
 */
@Service
@Primary
public class ElasticsearchHybridRetriever implements Retriever {

    private final EmbeddingGatewayService embeddingGatewayService;
    private final KnowledgeSearchProperties knowledgeSearchProperties;
    private final KnowledgeSearchGateway knowledgeSearchGateway;
    private final PgVectorRetriever pgVectorRetriever;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public ElasticsearchHybridRetriever(
            EmbeddingGatewayService embeddingGatewayService,
            KnowledgeSearchProperties knowledgeSearchProperties,
            KnowledgeSearchGateway knowledgeSearchGateway,
            PgVectorRetriever pgVectorRetriever,
            KnowledgeDocumentRepository knowledgeDocumentRepository
    ) {
        this.embeddingGatewayService = embeddingGatewayService;
        this.knowledgeSearchProperties = knowledgeSearchProperties;
        this.knowledgeSearchGateway = knowledgeSearchGateway;
        this.pgVectorRetriever = pgVectorRetriever;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    @Override
    public List<RetrievedKnowledgeChunk> retrieve(PreparedKnowledgeBase knowledgeBase, String query, int topK) {
        if (!knowledgeSearchProperties.isEnabled()) {
            return pgVectorRetriever.retrieve(knowledgeBase, query, topK);
        }

        float[] queryEmbedding = embeddingGatewayService.embedQuery(knowledgeBase.embeddingModelConfig(), query);
        int denseTopK = Math.max(topK, knowledgeSearchProperties.getDenseTopK());
        int lexicalTopK = Math.max(topK, knowledgeSearchProperties.getLexicalTopK());

        List<KnowledgeSearchMatch> denseMatches = knowledgeSearchGateway.denseSearch(
                knowledgeBase.knowledgeBaseId(),
                queryEmbedding,
                denseTopK
        );
        List<KnowledgeSearchMatch> lexicalMatches = knowledgeSearchGateway.lexicalSearch(
                knowledgeBase.knowledgeBaseId(),
                query,
                lexicalTopK
        );

        Set<Long> readyDocumentIds = readyDocumentIds(
                knowledgeBase.knowledgeBaseId(), denseMatches, lexicalMatches);

        Map<Long, FusedCandidate> fused = new LinkedHashMap<>();
        mergeMatches(fused, denseMatches, true, readyDocumentIds);
        mergeMatches(fused, lexicalMatches, false, readyDocumentIds);

        return fused.values().stream()
                .sorted(Comparator
                        .comparing((FusedCandidate candidate) -> candidate.fusionScore, Comparator.reverseOrder())
                        .thenComparing(FusedCandidate::bestRank))
                .limit(topK)
                .map(candidate -> candidate.toRetrievedKnowledgeChunk())
                .toList();
    }

    private void mergeMatches(
            Map<Long, FusedCandidate> fused,
            List<KnowledgeSearchMatch> matches,
            boolean dense,
            Set<Long> readyDocumentIds
    ) {
        for (KnowledgeSearchMatch match : matches) {
            if (!readyDocumentIds.contains(match.documentId())) {
                continue;
            }
            FusedCandidate candidate = fused.computeIfAbsent(match.chunkId(), ignored -> new FusedCandidate(
                    match.chunkId(),
                    match.documentId(),
                    match.documentName(),
                    match.chunkIndex(),
                    match.content()
            ));
            candidate.add(match, dense, knowledgeSearchProperties.getRrfK());
        }
    }

    private Set<Long> readyDocumentIds(
            Long knowledgeBaseId,
            List<KnowledgeSearchMatch> denseMatches,
            List<KnowledgeSearchMatch> lexicalMatches
    ) {
        List<Long> documentIds = java.util.stream.Stream.concat(denseMatches.stream(), lexicalMatches.stream())
                .map(KnowledgeSearchMatch::documentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(knowledgeDocumentRepository.findReadyDocumentIds(knowledgeBaseId, documentIds));
    }

    private static final class FusedCandidate {

        private final Long chunkId;
        private final Long documentId;
        private final String documentName;
        private final Integer chunkIndex;
        private final String content;
        private Double denseScore;
        private Integer denseRank;
        private Double lexicalScore;
        private Integer lexicalRank;
        private double fusionScore;

        private FusedCandidate(Long chunkId, Long documentId, String documentName, Integer chunkIndex, String content) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.documentName = documentName;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }

        private void add(KnowledgeSearchMatch match, boolean dense, int rrfK) {
            fusionScore += 1.0D / (rrfK + match.rank());
            if (dense) {
                denseScore = match.score();
                denseRank = match.rank();
            } else {
                lexicalScore = match.score();
                lexicalRank = match.rank();
            }
        }

        private int bestRank() {
            int left = denseRank == null ? Integer.MAX_VALUE : denseRank;
            int right = lexicalRank == null ? Integer.MAX_VALUE : lexicalRank;
            return Math.min(left, right);
        }

        private RetrievedKnowledgeChunk toRetrievedKnowledgeChunk() {
            return new RetrievedKnowledgeChunk(
                    chunkId,
                    documentId,
                    documentName,
                    chunkIndex,
                    content,
                    fusionScore,
                    denseScore,
                    denseRank,
                    lexicalScore,
                    lexicalRank,
                    denseRank != null && lexicalRank != null ? "BOTH" : (denseRank != null ? "DENSE" : "LEXICAL")
            );
        }
    }
}
