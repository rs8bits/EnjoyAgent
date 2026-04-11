package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.application.ModelGatewayProperties;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 基于百炼文本排序接口的二阶段重排器。
 */
@Service
public class DashScopeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final int MAX_DOCUMENTS = 100;

    private final AesCryptoService aesCryptoService;
    private final ModelGatewayProperties modelGatewayProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DashScopeReranker(
            AesCryptoService aesCryptoService,
            ModelGatewayProperties modelGatewayProperties,
            KnowledgeProperties knowledgeProperties,
            ObjectMapper objectMapper
    ) {
        this.aesCryptoService = aesCryptoService;
        this.modelGatewayProperties = modelGatewayProperties;
        this.knowledgeProperties = knowledgeProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public List<KnowledgeRetrievalHit> rerank(
            PreparedModelConfig rerankModelConfig,
            String query,
            List<RetrievedKnowledgeChunk> candidates,
            int finalTopK
    ) {
        if (rerankModelConfig == null) {
            return fallback(candidates, finalTopK);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedKnowledgeChunk> limitedCandidates = candidates.stream()
                .limit(Math.min(knowledgeProperties.getRecallTopK(), MAX_DOCUMENTS))
                .toList();
        try {
            validateRerankModelConfig(rerankModelConfig);
            String apiKey = resolveApiKey(rerankModelConfig);
            ObjectNode requestBody = buildRequestBody(rerankModelConfig, query, limitedCandidates);
            String rawResponse = restClient.post()
                    .uri(URI.create(knowledgeProperties.getRerankBaseUrl() + RERANK_PATH))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(String.class);

            return toHits(limitedCandidates, finalTopK, objectMapper.readTree(rawResponse));
        } catch (Exception ex) {
            log.warn(
                    "Rerank failed, fallback to recall order, modelName={}, baseUrl={}",
                    rerankModelConfig.modelName(),
                    knowledgeProperties.getRerankBaseUrl(),
                    ex
            );
            return fallback(limitedCandidates, finalTopK);
        }
    }

    private ObjectNode buildRequestBody(
            PreparedModelConfig rerankModelConfig,
            String query,
            List<RetrievedKnowledgeChunk> candidates
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", rerankModelConfig.modelName());

        ObjectNode input = body.putObject("input");
        input.putObject("query").put("text", query);
        ArrayNode documents = input.putArray("documents");
        for (RetrievedKnowledgeChunk candidate : candidates) {
            documents.addObject().put("text", candidate.content());
        }

        ObjectNode parameters = body.putObject("parameters");
        parameters.put("top_n", candidates.size());
        parameters.put("return_documents", false);
        return body;
    }

    private List<KnowledgeRetrievalHit> toHits(
            List<RetrievedKnowledgeChunk> candidates,
            int finalTopK,
            JsonNode response
    ) {
        Map<Integer, RankedResult> rankedResults = new HashMap<>();
        JsonNode results = response.path("output").path("results");
        for (int i = 0; i < results.size(); i++) {
            JsonNode result = results.get(i);
            rankedResults.put(
                    result.path("index").asInt(-1),
                    new RankedResult(doubleOrNull(result.get("relevance_score")), i + 1)
            );
        }

        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    RetrievedKnowledgeChunk candidate = candidates.get(index);
                    RankedResult ranked = rankedResults.get(index);
                    Integer rerankRank = ranked == null ? null : ranked.rank();
                    return new KnowledgeRetrievalHit(
                            candidate.id(),
                            candidate.documentId(),
                            candidate.documentName(),
                            candidate.chunkIndex(),
                            candidate.content(),
                            candidate.fusionScore(),
                            index + 1,
                            candidate.denseScore(),
                            candidate.denseRank(),
                            candidate.lexicalScore(),
                            candidate.lexicalRank(),
                            candidate.matchedBy(),
                            ranked == null ? null : ranked.score(),
                            rerankRank,
                            rerankRank != null && rerankRank <= finalTopK
                    );
                })
                .toList();
    }

    private List<KnowledgeRetrievalHit> fallback(List<RetrievedKnowledgeChunk> candidates, int finalTopK) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    RetrievedKnowledgeChunk candidate = candidates.get(index);
                    return new KnowledgeRetrievalHit(
                            candidate.id(),
                            candidate.documentId(),
                            candidate.documentName(),
                            candidate.chunkIndex(),
                            candidate.content(),
                            candidate.fusionScore(),
                            index + 1,
                            candidate.denseScore(),
                            candidate.denseRank(),
                            candidate.lexicalScore(),
                            candidate.lexicalRank(),
                            candidate.matchedBy(),
                            null,
                            null,
                            index < finalTopK
                    );
                })
                .toList();
    }

    private void validateRerankModelConfig(PreparedModelConfig rerankModelConfig) {
        if (!rerankModelConfig.provider().isOpenAiCompatibleRuntime()) {
            throw new ApiException("RERANK_PROVIDER_NOT_SUPPORTED", "Current runtime only supports OpenAI-compatible rerank providers", HttpStatus.BAD_REQUEST);
        }
        if (rerankModelConfig.modelType() != com.enjoy.agent.model.domain.enums.ModelType.RERANK) {
            throw new ApiException("RERANK_MODEL_TYPE_INVALID", "Current model config is not a rerank model", HttpStatus.BAD_REQUEST);
        }
    }

    private String resolveApiKey(PreparedModelConfig rerankModelConfig) {
        if (rerankModelConfig.credentialSource() == com.enjoy.agent.modelgateway.domain.enums.CredentialSource.USER) {
            if (!StringUtils.hasText(rerankModelConfig.credentialCiphertext())) {
                throw new ApiException("CREDENTIAL_SECRET_MISSING", "Credential secret is missing", HttpStatus.BAD_REQUEST);
            }
            return aesCryptoService.decrypt(rerankModelConfig.credentialCiphertext());
        }
        if (!StringUtils.hasText(modelGatewayProperties.getPlatformOpenaiApiKey())) {
            throw new ApiException("PLATFORM_MODEL_KEY_NOT_CONFIGURED", "Platform OpenAI key is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return modelGatewayProperties.getPlatformOpenaiApiKey().trim();
    }

    private Double doubleOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private record RankedResult(Double score, Integer rank) {
    }
}
