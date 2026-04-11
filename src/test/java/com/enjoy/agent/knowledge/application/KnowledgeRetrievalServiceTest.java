package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkRepository;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    @Mock
    private Retriever retriever;

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private Reranker reranker;

    private KnowledgeRetrievalService knowledgeRetrievalService;

    @BeforeEach
    void setUp() {
        KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setRecallTopK(6);
        knowledgeProperties.setRetrievalTopK(3);
        knowledgeRetrievalService = new KnowledgeRetrievalService(
                knowledgeChunkRepository,
                knowledgeProperties,
                queryRewriteService,
                retriever,
                reranker
        );
    }

    @Test
    void shouldSkipEmbeddingWhenKnowledgeBaseHasNoChunks() {
        PreparedKnowledgeBase knowledgeBase = preparedKnowledgeBase();
        when(knowledgeChunkRepository.existsByKnowledgeBase_Id(knowledgeBase.knowledgeBaseId())).thenReturn(false);

        KnowledgeRetrievalResult retrievalResult = knowledgeRetrievalService.retrieve(
                knowledgeBase,
                preparedChatModelConfig(),
                null,
                List.of(),
                "这是一个问题"
        );

        assertThat(retrievalResult.retrievalContext()).isNull();
        assertThat(retrievalResult.debug()).isNull();
        verify(knowledgeChunkRepository).existsByKnowledgeBase_Id(knowledgeBase.knowledgeBaseId());
        verifyNoInteractions(retriever, reranker, queryRewriteService);
    }

    @Test
    void shouldBuildReadableRetrievalContextAndDebugFromRerankedHits() {
        PreparedKnowledgeBase knowledgeBase = preparedKnowledgeBase();
        PreparedModelConfig chatModelConfig = preparedChatModelConfig();
        when(knowledgeChunkRepository.existsByKnowledgeBase_Id(knowledgeBase.knowledgeBaseId())).thenReturn(true);
        when(queryRewriteService.rewrite(chatModelConfig, null, List.of(), "这是一个问题"))
                .thenReturn(new QueryRewriteResult("这是一个问题", "产品知识库里这是一个问题", true));
        List<RetrievedKnowledgeChunk> candidates = List.of(
                new RetrievedKnowledgeChunk(1L, 10L, "员工手册.pdf", 0, "第一段命中文本", 0.91D, 0.91D, 1, null, null, "DENSE"),
                new RetrievedKnowledgeChunk(2L, 10L, "员工手册.pdf", 1, "第二段命中文本", 0.83D, 0.83D, 2, null, null, "DENSE")
        );
        when(retriever.retrieve(knowledgeBase, "产品知识库里这是一个问题", 6)).thenReturn(candidates);
        when(reranker.rerank(knowledgeBase.rerankModelConfig(), "产品知识库里这是一个问题", candidates, 3))
                .thenReturn(List.of(
                        new KnowledgeRetrievalHit(1L, 10L, "员工手册.pdf", 0, "第一段命中文本", 0.91D, 1, 0.91D, 1, null, null, "DENSE", 0.98D, 2, true),
                        new KnowledgeRetrievalHit(2L, 10L, "员工手册.pdf", 1, "第二段命中文本", 0.83D, 2, 0.83D, 2, null, null, "DENSE", 0.99D, 1, true)
                ));

        KnowledgeRetrievalResult retrievalResult = knowledgeRetrievalService.retrieve(
                knowledgeBase,
                chatModelConfig,
                null,
                List.of(),
                "这是一个问题"
        );

        assertThat(retrievalResult.retrievalContext())
                .contains("知识库“产品知识库”")
                .contains("[片段1]（员工手册.pdf #1）")
                .contains("第二段命中文本")
                .contains("[片段2]（员工手册.pdf #0）")
                .contains("第一段命中文本");
        assertThat(retrievalResult.debug()).isNotNull();
        assertThat(retrievalResult.debug().originalQuery()).isEqualTo("这是一个问题");
        assertThat(retrievalResult.debug().retrievalQuery()).isEqualTo("产品知识库里这是一个问题");
        assertThat(retrievalResult.debug().rewriteApplied()).isTrue();
        assertThat(retrievalResult.debug().recallTopK()).isEqualTo(2);
        assertThat(retrievalResult.debug().finalTopK()).isEqualTo(2);
        assertThat(retrievalResult.debug().rerankApplied()).isTrue();
        assertThat(retrievalResult.debug().rerankModel()).isEqualTo("qwen3-vl-rerank");
        assertThat(retrievalResult.debug().hits()).hasSize(2);
        assertThat(retrievalResult.debug().hits().get(0).documentName()).isEqualTo("员工手册.pdf");
        assertThat(retrievalResult.debug().hits().get(0).recallScore()).isEqualTo(0.91D);
        assertThat(retrievalResult.debug().hits().get(0).denseScore()).isEqualTo(0.91D);
        assertThat(retrievalResult.debug().hits().get(0).matchedBy()).isEqualTo("DENSE");
        assertThat(retrievalResult.debug().hits().get(0).rerankScore()).isEqualTo(0.98D);
        verify(retriever).retrieve(knowledgeBase, "产品知识库里这是一个问题", 6);
    }

    private PreparedModelConfig preparedChatModelConfig() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                CredentialSource.USER,
                12L,
                "ciphertext",
                null,
                null
        );
    }

    private PreparedKnowledgeBase preparedKnowledgeBase() {
        return new PreparedKnowledgeBase(
                1L,
                "产品知识库",
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.EMBEDDING,
                        "text-embedding-3-small",
                        CredentialSource.USER,
                        11L,
                        "ciphertext",
                        null,
                        null
                ),
                true,
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.RERANK,
                        "qwen3-vl-rerank",
                        CredentialSource.USER,
                        12L,
                        "ciphertext",
                        null,
                        null
                )
        );
    }
}
