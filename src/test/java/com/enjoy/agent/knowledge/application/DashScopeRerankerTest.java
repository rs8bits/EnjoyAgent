package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelGatewayProperties;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DashScopeRerankerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rerank_shouldReturnRecallAndRerankScores() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, """
                    {
                      "output": {
                        "results": [
                          {"index": 1, "relevance_score": 0.99},
                          {"index": 0, "relevance_score": 0.94}
                        ]
                      }
                    }
                    """);
        });
        server.start();

        AesCryptoService aesCryptoService = mock(AesCryptoService.class);
        when(aesCryptoService.decrypt("cipher")).thenReturn("dashscope-test-key");

        ModelGatewayProperties modelGatewayProperties = new ModelGatewayProperties();
        KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setRecallTopK(6);
        knowledgeProperties.setRetrievalTopK(2);
        knowledgeProperties.setRerankBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        DashScopeReranker reranker = new DashScopeReranker(
                aesCryptoService,
                modelGatewayProperties,
                knowledgeProperties,
                objectMapper
        );

        List<KnowledgeRetrievalHit> hits = reranker.rerank(
                preparedAuthModelConfig(),
                "试用期员工请假需提前多久申请",
                List.of(
                        new RetrievedKnowledgeChunk(1L, 10L, "员工手册.pdf", 0, "第一段", 0.91D, 0.90D, 1, null, null, "DENSE"),
                        new RetrievedKnowledgeChunk(2L, 10L, "员工手册.pdf", 1, "第二段", 0.83D, 0.82D, 2, null, null, "DENSE")
                ),
                1
        );

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).recallRank()).isEqualTo(1);
        assertThat(hits.get(0).rerankRank()).isEqualTo(2);
        assertThat(hits.get(0).selected()).isFalse();
        assertThat(hits.get(1).rerankRank()).isEqualTo(1);
        assertThat(hits.get(1).selected()).isTrue();
        assertThat(authorizationHeader.get()).isEqualTo("Bearer dashscope-test-key");

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("qwen3-vl-rerank");
        assertThat(body.path("parameters").path("top_n").asInt()).isEqualTo(2);
        assertThat(body.path("input").path("query").path("text").asText()).isEqualTo("试用期员工请假需提前多久申请");
        assertThat(body.path("input").path("documents")).hasSize(2);
    }

    @Test
    void rerank_shouldFallbackToRecallOrderWhenRemoteFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank", exchange ->
                json(exchange, """
                        {"message":"bad request"}
                        """, 500));
        server.start();

        AesCryptoService aesCryptoService = mock(AesCryptoService.class);
        when(aesCryptoService.decrypt("cipher")).thenReturn("dashscope-test-key");

        ModelGatewayProperties modelGatewayProperties = new ModelGatewayProperties();
        KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setRerankBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        DashScopeReranker reranker = new DashScopeReranker(
                aesCryptoService,
                modelGatewayProperties,
                knowledgeProperties,
                objectMapper
        );

        List<KnowledgeRetrievalHit> hits = reranker.rerank(
                preparedAuthModelConfig(),
                "query",
                List.of(
                        new RetrievedKnowledgeChunk(1L, 10L, "doc", 0, "第一段", 0.91D, 0.90D, 1, null, null, "DENSE"),
                        new RetrievedKnowledgeChunk(2L, 10L, "doc", 1, "第二段", 0.83D, 0.82D, 2, null, null, "DENSE")
                ),
                1
        );

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).selected()).isTrue();
        assertThat(hits.get(0).rerankScore()).isNull();
        assertThat(hits.get(1).selected()).isFalse();
    }

    private PreparedModelConfig preparedAuthModelConfig() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.RERANK,
                "qwen3-vl-rerank",
                CredentialSource.USER,
                1L,
                "cipher",
                BigDecimal.ZERO,
                512
        );
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        json(exchange, body, 200);
    }

    private void json(HttpExchange exchange, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
