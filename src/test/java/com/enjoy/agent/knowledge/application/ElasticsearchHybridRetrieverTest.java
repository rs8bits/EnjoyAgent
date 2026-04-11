package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElasticsearchHybridRetrieverTest {

    @Mock
    private EmbeddingGatewayService embeddingGatewayService;

    @Mock
    private KnowledgeSearchGateway knowledgeSearchGateway;

    @Mock
    private PgVectorRetriever pgVectorRetriever;

    private KnowledgeSearchProperties knowledgeSearchProperties;
    private ElasticsearchHybridRetriever retriever;

    @BeforeEach
    void setUp() {
        knowledgeSearchProperties = new KnowledgeSearchProperties();
        knowledgeSearchProperties.setEnabled(true);
        knowledgeSearchProperties.setDenseTopK(5);
        knowledgeSearchProperties.setLexicalTopK(5);
        knowledgeSearchProperties.setRrfK(60);
        retriever = new ElasticsearchHybridRetriever(
                embeddingGatewayService,
                knowledgeSearchProperties,
                knowledgeSearchGateway,
                pgVectorRetriever
        );
    }

    @Test
    void retrieve_shouldFuseDenseAndLexicalMatchesWithRrf() {
        PreparedKnowledgeBase knowledgeBase = preparedKnowledgeBase();
        float[] queryEmbedding = new float[]{0.1F, 0.2F};
        when(embeddingGatewayService.embedQuery(knowledgeBase.embeddingModelConfig(), "请假规则"))
                .thenReturn(queryEmbedding);
        when(knowledgeSearchGateway.denseSearch(1L, queryEmbedding, 5))
                .thenReturn(List.of(
                        new KnowledgeSearchMatch(1L, 10L, "员工手册.pdf", 0, "第一段", 0.91D, 1),
                        new KnowledgeSearchMatch(2L, 10L, "员工手册.pdf", 1, "第二段", 0.82D, 2)
                ));
        when(knowledgeSearchGateway.lexicalSearch(1L, "请假规则", 5))
                .thenReturn(List.of(
                        new KnowledgeSearchMatch(2L, 10L, "员工手册.pdf", 1, "第二段", 11.2D, 1),
                        new KnowledgeSearchMatch(3L, 11L, "试用期制度.pdf", 0, "第三段", 10.5D, 2)
                ));

        List<RetrievedKnowledgeChunk> matches = retriever.retrieve(knowledgeBase, "请假规则", 3);

        assertThat(matches).hasSize(3);
        assertThat(matches.get(0).id()).isEqualTo(2L);
        assertThat(matches.get(0).matchedBy()).isEqualTo("BOTH");
        assertThat(matches.get(0).denseRank()).isEqualTo(2);
        assertThat(matches.get(0).lexicalRank()).isEqualTo(1);

        assertThat(matches.get(1).id()).isEqualTo(1L);
        assertThat(matches.get(1).matchedBy()).isEqualTo("DENSE");

        assertThat(matches.get(2).id()).isEqualTo(3L);
        assertThat(matches.get(2).matchedBy()).isEqualTo("LEXICAL");
    }

    @Test
    void retrieve_shouldFallbackToPgVectorWhenSearchDisabled() {
        knowledgeSearchProperties.setEnabled(false);
        PreparedKnowledgeBase knowledgeBase = preparedKnowledgeBase();
        List<RetrievedKnowledgeChunk> fallback = List.of(
                new RetrievedKnowledgeChunk(1L, 10L, "员工手册.pdf", 0, "第一段", 0.91D, 0.91D, 1, null, null, "DENSE")
        );
        when(pgVectorRetriever.retrieve(knowledgeBase, "请假规则", 3)).thenReturn(fallback);

        List<RetrievedKnowledgeChunk> matches = retriever.retrieve(knowledgeBase, "请假规则", 3);

        assertThat(matches).isEqualTo(fallback);
        verifyNoInteractions(embeddingGatewayService, knowledgeSearchGateway);
    }

    private PreparedKnowledgeBase preparedKnowledgeBase() {
        return new PreparedKnowledgeBase(
                1L,
                "员工制度库",
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.EMBEDDING,
                        "text-embedding-v3",
                        CredentialSource.USER,
                        1L,
                        "cipher",
                        null,
                        null
                ),
                true,
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.RERANK,
                        "qwen3-vl-rerank",
                        CredentialSource.USER,
                        2L,
                        "cipher",
                        null,
                        null
                )
        );
    }
}
