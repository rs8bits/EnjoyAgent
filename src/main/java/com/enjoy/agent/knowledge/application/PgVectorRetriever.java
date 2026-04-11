package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkJdbcRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 基于 pgvector 的候选召回器。
 */
@Service
public class PgVectorRetriever implements Retriever {

    private final EmbeddingGatewayService embeddingGatewayService;
    private final KnowledgeChunkJdbcRepository knowledgeChunkJdbcRepository;

    public PgVectorRetriever(
            EmbeddingGatewayService embeddingGatewayService,
            KnowledgeChunkJdbcRepository knowledgeChunkJdbcRepository
    ) {
        this.embeddingGatewayService = embeddingGatewayService;
        this.knowledgeChunkJdbcRepository = knowledgeChunkJdbcRepository;
    }

    @Override
    public List<RetrievedKnowledgeChunk> retrieve(PreparedKnowledgeBase knowledgeBase, String query, int topK) {
        List<RetrievedKnowledgeChunk> matches = knowledgeChunkJdbcRepository.searchTopK(
                knowledgeBase.knowledgeBaseId(),
                embeddingGatewayService.embedQuery(knowledgeBase.embeddingModelConfig(), query),
                topK
        );
        return java.util.stream.IntStream.range(0, matches.size())
                .mapToObj(index -> {
                    RetrievedKnowledgeChunk match = matches.get(index);
                    return new RetrievedKnowledgeChunk(
                            match.id(),
                            match.documentId(),
                            match.documentName(),
                            match.chunkIndex(),
                            match.content(),
                            match.fusionScore(),
                            match.fusionScore(),
                            index + 1,
                            null,
                            null,
                            "DENSE"
                    );
                })
                .toList();
    }
}
