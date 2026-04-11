package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelGatewayProperties;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Embedding 调用网关。
 */
@Service
public class EmbeddingGatewayService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGatewayService.class);

    private final AesCryptoService aesCryptoService;
    private final ModelGatewayProperties modelGatewayProperties;
    private final KnowledgeProperties knowledgeProperties;

    public EmbeddingGatewayService(
            AesCryptoService aesCryptoService,
            ModelGatewayProperties modelGatewayProperties,
            KnowledgeProperties knowledgeProperties
    ) {
        this.aesCryptoService = aesCryptoService;
        this.modelGatewayProperties = modelGatewayProperties;
        this.knowledgeProperties = knowledgeProperties;
    }

    /**
     * 批量生成文本向量。
     */
    public List<float[]> embedTexts(PreparedModelConfig modelConfig, List<String> texts) {
        validateEmbeddingModelConfig(modelConfig);
        if (texts == null || texts.isEmpty()) {
            throw new ApiException("EMBEDDING_INPUT_EMPTY", "Embedding input is empty", HttpStatus.BAD_REQUEST);
        }

        try {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model(modelConfig.modelName())
                    .dimensions(knowledgeProperties.getEmbeddingDimensions())
                    .build();

            String resolvedBaseUrl = resolveBaseUrl(modelConfig);
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(resolvedBaseUrl)
                    .apiKey(resolveApiKey(modelConfig))
                    .build();

            OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options);
            return embedInBatches(embeddingModel, options, texts);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error(
                    "Embedding 调用失败，modelName={}, baseUrl={}",
                    modelConfig.modelName(),
                    resolveBaseUrl(modelConfig),
                    ex
            );
            throw new ApiException("EMBEDDING_INVOCATION_FAILED", "Embedding invocation failed", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * 生成单条查询向量。
     */
    public float[] embedQuery(PreparedModelConfig modelConfig, String query) {
        return embedTexts(modelConfig, List.of(query)).getFirst();
    }

    /**
     * 分批调用 embedding 接口，避免一次请求切片过多导致上游拒绝。
     */
    private List<float[]> embedInBatches(
            OpenAiEmbeddingModel embeddingModel,
            OpenAiEmbeddingOptions options,
            List<String> texts
    ) {
        int batchSize = 10;
        List<float[]> vectors = new java.util.ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(batch, options));
            vectors.addAll(response.getResults().stream().map(Embedding::getOutput).toList());
        }
        return vectors;
    }

    /**
     * 校验 embedding 模型配置。
     */
    private void validateEmbeddingModelConfig(PreparedModelConfig modelConfig) {
        if (modelConfig.modelType() != ModelType.EMBEDDING) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Current model config is not an embedding model", HttpStatus.BAD_REQUEST);
        }
        if (!modelConfig.provider().isOpenAiCompatibleRuntime()) {
            throw new ApiException("MODEL_PROVIDER_NOT_SUPPORTED", "Current runtime only supports OpenAI-compatible embedding providers", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 解析最终的 embedding 调用 API Key。
     */
    private String resolveApiKey(PreparedModelConfig modelConfig) {
        if (modelConfig.credentialSource() == com.enjoy.agent.modelgateway.domain.enums.CredentialSource.USER) {
            if (modelConfig.credentialCiphertext() == null || modelConfig.credentialCiphertext().isBlank()) {
                throw new ApiException("CREDENTIAL_SECRET_MISSING", "Credential secret is missing", HttpStatus.BAD_REQUEST);
            }
            return aesCryptoService.decrypt(modelConfig.credentialCiphertext());
        }

        String platformKey = modelGatewayProperties.getPlatformOpenaiApiKey();
        if (platformKey == null || platformKey.isBlank()) {
            throw new ApiException("PLATFORM_MODEL_KEY_NOT_CONFIGURED", "Platform OpenAI key is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return platformKey.trim();
    }

    private String resolveBaseUrl(PreparedModelConfig modelConfig) {
        if (modelConfig.baseUrl() != null && !modelConfig.baseUrl().isBlank()) {
            return modelConfig.baseUrl();
        }
        String providerDefault = modelConfig.provider().defaultCompatibleBaseUrl();
        if (providerDefault != null) {
            return providerDefault;
        }
        return modelGatewayProperties.getOpenaiBaseUrl();
    }
}
