package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch HTTP 网关。
 */
@Service
public class KnowledgeSearchGateway {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchGateway.class);

    private final KnowledgeSearchProperties knowledgeSearchProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public KnowledgeSearchGateway(
            KnowledgeSearchProperties knowledgeSearchProperties,
            KnowledgeProperties knowledgeProperties,
            ObjectMapper objectMapper
    ) {
        this.knowledgeSearchProperties = knowledgeSearchProperties;
        this.knowledgeProperties = knowledgeProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(knowledgeSearchProperties.getConnectTimeoutSeconds()))
                .build();
    }

    public boolean isEnabled() {
        return knowledgeSearchProperties.isEnabled();
    }

    /**
     * 启动时确保索引存在。
     */
    public void ensureIndexExists() {
        if (!isEnabled()) {
            return;
        }
        try {
            HttpResponse<String> response = send("HEAD", "/" + knowledgeSearchProperties.getIndexName(), null, "application/json");
            if (response.statusCode() == 200) {
                return;
            }
            if (response.statusCode() != 404) {
                throw new ApiException(
                        "KNOWLEDGE_SEARCH_INDEX_CHECK_FAILED",
                        "Failed to check Elasticsearch index",
                        HttpStatus.BAD_GATEWAY
                );
            }

            ObjectNode body = buildCreateIndexRequest();
            HttpResponse<String> createResponse = send(
                    "PUT",
                    "/" + knowledgeSearchProperties.getIndexName(),
                    objectMapper.writeValueAsString(body),
                    "application/json"
            );
            if (createResponse.statusCode() >= 300) {
                throw new ApiException(
                        "KNOWLEDGE_SEARCH_INDEX_CREATE_FAILED",
                        "Failed to create Elasticsearch index",
                        HttpStatus.BAD_GATEWAY
                );
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to initialize Elasticsearch knowledge index", ex);
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_INDEX_CREATE_FAILED",
                    "Failed to initialize Elasticsearch knowledge index",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    /**
     * 批量 upsert 知识切片到 Elasticsearch。
     */
    public void bulkUpsert(List<KnowledgeChunkSearchDocument> documents) {
        if (!isEnabled() || documents == null || documents.isEmpty()) {
            return;
        }
        try {
            String payload = buildBulkPayload(documents);
            HttpResponse<String> response = send("POST", "/_bulk?refresh=wait_for", payload, "application/x-ndjson");
            if (response.statusCode() >= 300) {
                throw new ApiException(
                        "KNOWLEDGE_SEARCH_BULK_UPSERT_FAILED",
                        "Failed to upsert Elasticsearch knowledge chunks",
                        HttpStatus.BAD_GATEWAY
                );
            }
            JsonNode json = objectMapper.readTree(response.body());
            if (json.path("errors").asBoolean(false)) {
                throw new ApiException(
                        "KNOWLEDGE_SEARCH_BULK_UPSERT_FAILED",
                        "Elasticsearch bulk upsert contains failed items",
                        HttpStatus.BAD_GATEWAY
                );
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to bulk upsert Elasticsearch knowledge chunks", ex);
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_BULK_UPSERT_FAILED",
                    "Failed to bulk upsert Elasticsearch knowledge chunks",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    /**
     * 删除某个文档在 Elasticsearch 中的全部知识切片。
     */
    public void deleteByDocumentId(Long documentId) {
        if (!isEnabled() || documentId == null) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("query")
                    .putObject("term")
                    .put("documentId", documentId);
            HttpResponse<String> response = send(
                    "POST",
                    "/" + knowledgeSearchProperties.getIndexName() + "/_delete_by_query?refresh=true",
                    objectMapper.writeValueAsString(body),
                    "application/json"
            );
            if (response.statusCode() >= 300) {
                throw new ApiException(
                        "KNOWLEDGE_SEARCH_DELETE_FAILED",
                        "Failed to delete Elasticsearch knowledge chunks",
                        HttpStatus.BAD_GATEWAY
                );
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to delete Elasticsearch knowledge chunks, documentId={}", documentId, ex);
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_DELETE_FAILED",
                    "Failed to delete Elasticsearch knowledge chunks",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    /**
     * 语义召回。
     */
    public List<KnowledgeSearchMatch> denseSearch(Long knowledgeBaseId, float[] queryEmbedding, int topK) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("size", topK);
            ArrayNode source = body.putArray("_source");
            source.add("chunkId");
            source.add("documentId");
            source.add("documentName");
            source.add("chunkIndex");
            source.add("content");

            ObjectNode knn = body.putObject("knn");
            knn.put("field", "contentVector");
            ArrayNode vector = knn.putArray("query_vector");
            for (float value : queryEmbedding) {
                vector.add(value);
            }
            knn.put("k", topK);
            knn.put("num_candidates", Math.max(topK * 4, 20));
            ArrayNode filter = knn.putArray("filter");
            filter.addObject().putObject("term").put("knowledgeBaseId", knowledgeBaseId);

            JsonNode json = executeSearch(body);
            return toMatches(json);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Dense Elasticsearch retrieval failed, knowledgeBaseId={}", knowledgeBaseId, ex);
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_DENSE_FAILED",
                    "Dense Elasticsearch retrieval failed",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    /**
     * 关键词召回。
     */
    public List<KnowledgeSearchMatch> lexicalSearch(Long knowledgeBaseId, String query, int topK) {
        if (!isEnabled() || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("size", topK);
            ArrayNode source = body.putArray("_source");
            source.add("chunkId");
            source.add("documentId");
            source.add("documentName");
            source.add("chunkIndex");
            source.add("content");

            ObjectNode bool = body.putObject("query").putObject("bool");
            ArrayNode filter = bool.putArray("filter");
            filter.addObject().putObject("term").put("knowledgeBaseId", knowledgeBaseId);
            ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
            multiMatch.put("query", query);
            ArrayNode fields = multiMatch.putArray("fields");
            fields.add("content^3");
            fields.add("documentName^2");
            multiMatch.put("type", "best_fields");
            multiMatch.put("operator", "or");

            JsonNode json = executeSearch(body);
            return toMatches(json);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Lexical Elasticsearch retrieval failed, knowledgeBaseId={}", knowledgeBaseId, ex);
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_LEXICAL_FAILED",
                    "Lexical Elasticsearch retrieval failed",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    private JsonNode executeSearch(ObjectNode body) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/" + knowledgeSearchProperties.getIndexName() + "/_search",
                objectMapper.writeValueAsString(body),
                "application/json"
        );
        if (response.statusCode() >= 300) {
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_QUERY_FAILED",
                    "Failed to query Elasticsearch knowledge index",
                    HttpStatus.BAD_GATEWAY
            );
        }
        return objectMapper.readTree(response.body());
    }

    private List<KnowledgeSearchMatch> toMatches(JsonNode response) {
        List<KnowledgeSearchMatch> matches = new ArrayList<>();
        JsonNode hits = response.path("hits").path("hits");
        for (int i = 0; i < hits.size(); i++) {
            JsonNode hit = hits.get(i);
            JsonNode source = hit.path("_source");
            matches.add(new KnowledgeSearchMatch(
                    source.path("chunkId").asLong(),
                    source.path("documentId").asLong(),
                    source.path("documentName").asText(null),
                    source.path("chunkIndex").isMissingNode() ? null : source.path("chunkIndex").asInt(),
                    source.path("content").asText(null),
                    hit.path("_score").isMissingNode() || hit.path("_score").isNull() ? null : hit.path("_score").asDouble(),
                    i + 1
            ));
        }
        return matches;
    }

    private ObjectNode buildCreateIndexRequest() {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode settings = body.putObject("settings");
        settings.putObject("index").put("number_of_shards", 1).put("number_of_replicas", 0);

        ObjectNode properties = body.putObject("mappings").putObject("properties");
        properties.putObject("chunkId").put("type", "long");
        properties.putObject("tenantId").put("type", "long");
        properties.putObject("knowledgeBaseId").put("type", "long");
        properties.putObject("documentId").put("type", "long");

        ObjectNode documentName = properties.putObject("documentName");
        documentName.put("type", "text");
        documentName.put("analyzer", knowledgeSearchProperties.getIndexAnalyzer());
        documentName.put("search_analyzer", knowledgeSearchProperties.getSearchAnalyzer());
        documentName.putObject("fields").putObject("keyword").put("type", "keyword");

        ObjectNode content = properties.putObject("content");
        content.put("type", "text");
        content.put("analyzer", knowledgeSearchProperties.getIndexAnalyzer());
        content.put("search_analyzer", knowledgeSearchProperties.getSearchAnalyzer());

        properties.putObject("chunkIndex").put("type", "integer");
        properties.putObject("createdAt").put("type", "date");
        properties.putObject("updatedAt").put("type", "date");

        ObjectNode vector = properties.putObject("contentVector");
        vector.put("type", "dense_vector");
        vector.put("dims", knowledgeProperties.getEmbeddingDimensions());
        vector.put("index", true);
        vector.put("similarity", "cosine");
        return body;
    }

    private String buildBulkPayload(List<KnowledgeChunkSearchDocument> documents) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeChunkSearchDocument document : documents) {
            ObjectNode action = objectMapper.createObjectNode();
            action.putObject("index")
                    .put("_index", knowledgeSearchProperties.getIndexName())
                    .put("_id", String.valueOf(document.chunkId()));
            builder.append(objectMapper.writeValueAsString(action)).append('\n');

            ObjectNode source = objectMapper.createObjectNode();
            source.put("chunkId", document.chunkId());
            source.put("tenantId", document.tenantId());
            source.put("knowledgeBaseId", document.knowledgeBaseId());
            source.put("documentId", document.documentId());
            source.put("documentName", document.documentName());
            source.put("chunkIndex", document.chunkIndex());
            source.put("content", document.content());
            source.put("createdAt", document.createdAt().toString());
            source.put("updatedAt", document.updatedAt().toString());
            ArrayNode vector = source.putArray("contentVector");
            for (float value : document.contentVector()) {
                vector.add(value);
            }
            builder.append(objectMapper.writeValueAsString(source)).append('\n');
        }
        return builder.toString();
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        if (!StringUtils.hasText(knowledgeSearchProperties.getBaseUrl())) {
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_BASE_URL_MISSING",
                    "Elasticsearch base URL is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(knowledgeSearchProperties.getBaseUrl().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(knowledgeSearchProperties.getReadTimeoutSeconds()))
                .header("Accept", "application/json");
        if (StringUtils.hasText(contentType)) {
            builder.header("Content-Type", contentType);
        }
        if (StringUtils.hasText(knowledgeSearchProperties.getUsername())) {
            String credentials = knowledgeSearchProperties.getUsername() + ":" +
                    (knowledgeSearchProperties.getPassword() == null ? "" : knowledgeSearchProperties.getPassword());
            builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))
            );
        }
        if ("HEAD".equals(method)) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
