package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Model Gateway 基础调用服务。
 */
@Service
public class ModelGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayService.class);

    private final AesCryptoService aesCryptoService;
    private final ModelGatewayProperties modelGatewayProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final HttpClient httpClient;

    public ModelGatewayService(
            AesCryptoService aesCryptoService,
            ModelGatewayProperties modelGatewayProperties,
            ObjectMapper objectMapper
    ) {
        this.aesCryptoService = aesCryptoService;
        this.modelGatewayProperties = modelGatewayProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 调用模型生成一条普通文本回复。
     */
    public ModelGatewayResult generateReply(PreparedChatTurn preparedChatTurn) {
        List<ModelGatewayConversationMessage> conversationMessages = preparedChatTurn.historyMessages()
                .stream()
                .map(this::toConversationMessage)
                .toList();
        ModelGatewayChatCompletion completion = generateChatCompletion(
                preparedChatTurn.modelConfig(),
                preparedChatTurn.systemPrompt(),
                preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                preparedChatTurn.retrievalContext(),
                conversationMessages,
                List.of()
        );
        if (!completion.toolCalls().isEmpty()) {
            throw new ModelGatewayInvocationException(
                    "MODEL_UNEXPECTED_TOOL_CALL",
                    "Model returned tool calls for a plain chat request",
                    HttpStatus.BAD_GATEWAY,
                    completion.provider(),
                    completion.modelName(),
                    completion.credentialSource(),
                    completion.credentialId(),
                    completion.latencyMs(),
                    null
            );
        }
        return completion.toResult();
    }

    /**
     * 生成一段不带工具调用的纯文本结果。
     */
    public String generateText(
            PreparedModelConfig modelConfig,
            String systemPrompt,
            String userPrompt
    ) {
        ModelGatewayChatCompletion completion = generateChatCompletion(
                modelConfig,
                systemPrompt,
                null,
                null,
                List.of(ModelGatewayConversationMessage.user(userPrompt)),
                List.of()
        );
        if (!completion.toolCalls().isEmpty()) {
            throw new ModelGatewayInvocationException(
                    "MODEL_UNEXPECTED_TOOL_CALL",
                    "Model returned tool calls for a plain text request",
                    HttpStatus.BAD_GATEWAY,
                    completion.provider(),
                    completion.modelName(),
                    completion.credentialSource(),
                    completion.credentialId(),
                    completion.latencyMs(),
                    null
            );
        }
        if (!StringUtils.hasText(completion.content())) {
            throw new ModelGatewayInvocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    completion.provider(),
                    completion.modelName(),
                    completion.credentialSource(),
                    completion.credentialId(),
                    completion.latencyMs(),
                    null
            );
        }
        return completion.content();
    }

    /**
     * 以流式方式调用模型生成文本回复。
     */
    public ModelGatewayResult streamReply(
            PreparedChatTurn preparedChatTurn,
            Consumer<String> deltaConsumer
    ) {
        List<ModelGatewayConversationMessage> conversationMessages = preparedChatTurn.historyMessages()
                .stream()
                .map(this::toConversationMessage)
                .toList();
        return streamChatCompletion(preparedChatTurn, conversationMessages, deltaConsumer);
    }

    /**
     * 调用模型生成聊天补全过程结果，允许返回工具调用请求。
     */
    public ModelGatewayChatCompletion generateChatCompletion(
            PreparedChatTurn preparedChatTurn,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return generateChatCompletion(
                preparedChatTurn.modelConfig(),
                preparedChatTurn.systemPrompt(),
                preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                preparedChatTurn.retrievalContext(),
                conversationMessages,
                tools
        );
    }

    private ModelGatewayChatCompletion generateChatCompletion(
            PreparedModelConfig modelConfig,
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        CredentialSource credentialSource = resolveCredentialSource(modelConfig);
        long startNanos = System.nanoTime();
        String resolvedBaseUrl = resolveBaseUrl(modelConfig);

        try {
            validateModelConfig(modelConfig, credentialSource);
            String apiKey = resolveApiKey(modelConfig, credentialSource);

            ObjectNode requestBody = buildRequestBody(
                    modelConfig,
                    credentialSource,
                    systemPrompt,
                    sessionMemorySummary,
                    retrievalContext,
                    conversationMessages,
                    tools,
                    false
            );

            String rawResponse = restClient.post()
                    .uri(URI.create(resolvedBaseUrl + "/v1/chat/completions"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(toJson(requestBody, modelConfig, credentialSource))
                    .retrieve()
                    .body(String.class);

            JsonNode response = readJson(rawResponse, modelConfig, credentialSource);
            JsonNode message = response.path("choices").path(0).path("message");
            String content = extractAssistantContent(message);
            List<ModelGatewayToolCall> toolCalls = extractToolCalls(message);
            JsonNode usage = response.path("usage");

            if (!StringUtils.hasText(content) && toolCalls.isEmpty()) {
                throw new ModelGatewayInvocationException(
                        "MODEL_EMPTY_RESPONSE",
                        "Model returned empty response",
                        HttpStatus.BAD_GATEWAY,
                        modelConfig.provider(),
                        modelConfig.modelName(),
                        credentialSource,
                        modelConfig.credentialId(),
                        elapsedMillis(startNanos),
                        null
                );
            }

            return new ModelGatewayChatCompletion(
                    StringUtils.hasText(content) ? content.trim() : null,
                    toolCalls,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    intOrNull(usage.get("prompt_tokens")),
                    intOrNull(usage.get("completion_tokens")),
                    intOrNull(usage.get("total_tokens"))
            );
        } catch (ModelGatewayInvocationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error(
                    "模型调用失败，provider={}, modelName={}, credentialSource={}, baseUrl={}",
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    resolvedBaseUrl,
                    ex
            );
            throw new ModelGatewayInvocationException(
                    "MODEL_INVOCATION_FAILED",
                    "Model invocation failed",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    ex
            );
        }
    }

    private ModelGatewayResult streamChatCompletion(
            PreparedChatTurn preparedChatTurn,
            List<ModelGatewayConversationMessage> conversationMessages,
            Consumer<String> deltaConsumer
    ) {
        PreparedModelConfig modelConfig = preparedChatTurn.modelConfig();
        CredentialSource credentialSource = resolveCredentialSource(modelConfig);
        long startNanos = System.nanoTime();
        String resolvedBaseUrl = resolveBaseUrl(modelConfig);

        try {
            validateModelConfig(modelConfig, credentialSource);
            String apiKey = resolveApiKey(modelConfig, credentialSource);
            ObjectNode requestBody = buildRequestBody(
                    modelConfig,
                    credentialSource,
                    preparedChatTurn.systemPrompt(),
                    preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                    preparedChatTurn.retrievalContext(),
                    conversationMessages,
                    List.of(),
                    true
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedBaseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE + ", " + MediaType.APPLICATION_JSON_VALUE)
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody, modelConfig, credentialSource)))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw new ModelGatewayInvocationException(
                            "MODEL_INVOCATION_FAILED",
                            "Model invocation failed with HTTP " + response.statusCode()
                                    + appendBodySnippet(readFully(body)),
                            HttpStatus.BAD_GATEWAY,
                            modelConfig.provider(),
                            modelConfig.modelName(),
                            credentialSource,
                            modelConfig.credentialId(),
                            elapsedMillis(startNanos),
                            null
                    );
                }
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            try (InputStream body = response.body()) {
                if (contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
                    return readJsonStreamingFallback(body, modelConfig, credentialSource, deltaConsumer, startNanos);
                }
                return readSseStream(body, modelConfig, credentialSource, deltaConsumer, startNanos);
            }
        } catch (ModelGatewayStreamConsumerException ex) {
            throw ex;
        } catch (ModelGatewayInvocationException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayInvocationException(
                    "MODEL_INVOCATION_INTERRUPTED",
                    "Model streaming invocation was interrupted",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    ex
            );
        } catch (Exception ex) {
            log.error(
                    "模型流式调用失败，provider={}, modelName={}, credentialSource={}, baseUrl={}",
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    resolvedBaseUrl,
                    ex
            );
            throw new ModelGatewayInvocationException(
                    "MODEL_INVOCATION_FAILED",
                    "Model invocation failed",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    ex
            );
        }
    }

    private ObjectNode buildRequestBody(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools,
            boolean stream
    ) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", modelConfig.modelName());
        if (stream) {
            requestBody.put("stream", true);
            requestBody.putObject("stream_options")
                    .put("include_usage", true);
        }

        BigDecimal temperature = modelConfig.temperature();
        if (temperature != null) {
            requestBody.put("temperature", temperature.doubleValue());
        }
        if (modelConfig.maxTokens() != null) {
            requestBody.put("max_tokens", modelConfig.maxTokens());
        }

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
        if (StringUtils.hasText(sessionMemorySummary)) {
            messages.addObject()
                    .put("role", "system")
                    .put("content", "以下是当前会话的长期摘要记忆，请保持对话连续性，并据此补足窗口外但仍然重要的上下文。\n\n"
                            + sessionMemorySummary);
        }
        if (StringUtils.hasText(retrievalContext)) {
            messages.addObject()
                    .put("role", "system")
                    .put("content", "以下是与当前问题相关的知识库内容，请优先依据这些内容回答；如果知识库内容不足以回答，请明确说明并谨慎补充。\n\n"
                            + retrievalContext);
        }
        for (ModelGatewayConversationMessage conversationMessage : conversationMessages) {
            ObjectNode messageNode = messages.addObject().put("role", conversationMessage.role());
            if ("tool".equals(conversationMessage.role())) {
                messageNode.put("tool_call_id", conversationMessage.toolCallId());
                messageNode.put("content", conversationMessage.content());
                continue;
            }
            if (conversationMessage.toolCalls() != null && !conversationMessage.toolCalls().isEmpty()) {
                messageNode.putNull("content");
                ArrayNode toolCalls = messageNode.putArray("tool_calls");
                for (ModelGatewayToolCall toolCall : conversationMessage.toolCalls()) {
                    toolCalls.addObject()
                            .put("id", toolCall.id())
                            .put("type", "function")
                            .set("function", objectMapper.createObjectNode()
                                    .put("name", toolCall.name())
                                    .put("arguments", toolCall.argumentsJson()));
                }
            } else if (conversationMessage.content() == null) {
                messageNode.putNull("content");
            } else {
                messageNode.put("content", conversationMessage.content());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            requestBody.put("tool_choice", "auto");
            for (ModelGatewayToolDefinition tool : tools) {
                toolsArray.addObject()
                        .put("type", "function")
                        .set("function", objectMapper.createObjectNode()
                                .put("name", tool.name())
                                .put("description", tool.description() == null ? "" : tool.description())
                                .set("parameters", parseSchema(tool, modelConfig, credentialSource)));
            }
        }

        return requestBody;
    }

    private ModelGatewayResult readJsonStreamingFallback(
            InputStream body,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            Consumer<String> deltaConsumer,
            long startNanos
    ) throws IOException {
        String rawResponse = readFully(body);
        JsonNode response = readJson(rawResponse, modelConfig, credentialSource);
        JsonNode message = response.path("choices").path(0).path("message");
        String content = extractAssistantContent(message);
        if (!StringUtils.hasText(content)) {
            throw new ModelGatewayInvocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    null
            );
        }
        deltaConsumer.accept(content);
        JsonNode usage = response.path("usage");
        return new ModelGatewayResult(
                content.trim(),
                modelConfig.provider(),
                modelConfig.modelName(),
                credentialSource,
                modelConfig.credentialId(),
                elapsedMillis(startNanos),
                intOrNull(usage.get("prompt_tokens")),
                intOrNull(usage.get("completion_tokens")),
                intOrNull(usage.get("total_tokens"))
        );
    }

    private ModelGatewayResult readSseStream(
            InputStream body,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            Consumer<String> deltaConsumer,
            long startNanos
    ) throws IOException {
        StreamingAccumulator accumulator = new StreamingAccumulator();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder eventData = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    processSseEventData(eventData.toString(), accumulator, modelConfig, credentialSource, deltaConsumer);
                    eventData.setLength(0);
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                if (!eventData.isEmpty()) {
                    eventData.append('\n');
                }
                eventData.append(line.substring(5).trim());
            }
            processSseEventData(eventData.toString(), accumulator, modelConfig, credentialSource, deltaConsumer);
        }

        if (!StringUtils.hasText(accumulator.content())) {
            throw new ModelGatewayInvocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    elapsedMillis(startNanos),
                    null
            );
        }

        return new ModelGatewayResult(
                accumulator.content().trim(),
                modelConfig.provider(),
                modelConfig.modelName(),
                credentialSource,
                modelConfig.credentialId(),
                elapsedMillis(startNanos),
                accumulator.promptTokens,
                accumulator.completionTokens,
                accumulator.totalTokens
        );
    }

    private void processSseEventData(
            String eventData,
            StreamingAccumulator accumulator,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            Consumer<String> deltaConsumer
    ) {
        if (!StringUtils.hasText(eventData) || "[DONE]".equals(eventData.trim())) {
            return;
        }

        JsonNode json = readJson(eventData, modelConfig, credentialSource);
        if (json.has("error")) {
            String message = json.path("error").path("message").asText("Model invocation failed");
            throw new ModelGatewayInvocationException(
                    "MODEL_INVOCATION_FAILED",
                    message,
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    null
            );
        }

        JsonNode usage = json.get("usage");
        if (usage != null && !usage.isNull()) {
            accumulator.promptTokens = intOrNull(usage.get("prompt_tokens"));
            accumulator.completionTokens = intOrNull(usage.get("completion_tokens"));
            accumulator.totalTokens = intOrNull(usage.get("total_tokens"));
        }

        JsonNode choice = json.path("choices").path(0);
        if (choice.isMissingNode()) {
            return;
        }

        String delta = extractStreamingDelta(choice.path("delta"));
        if (!StringUtils.hasText(delta)) {
            return;
        }
        accumulator.contentBuilder.append(delta);
        deltaConsumer.accept(delta);
    }

    private String extractStreamingDelta(JsonNode deltaNode) {
        if (deltaNode == null || deltaNode.isNull()) {
            return null;
        }
        JsonNode contentNode = deltaNode.get("content");
        if (contentNode == null || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                if (part.hasNonNull("text")) {
                    parts.add(part.path("text").asText());
                } else if (part.hasNonNull("content")) {
                    parts.add(part.path("content").asText());
                }
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        return null;
    }

    private JsonNode parseSchema(
            ModelGatewayToolDefinition tool,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource
    ) {
        if (!StringUtils.hasText(tool.inputSchemaJson())) {
            return objectMapper.createObjectNode()
                    .put("type", "object")
                    .set("properties", objectMapper.createObjectNode());
        }
        try {
            return objectMapper.readTree(tool.inputSchemaJson());
        } catch (JsonProcessingException ex) {
            throw new ModelGatewayInvocationException(
                    "MODEL_TOOL_SCHEMA_INVALID",
                    "Tool schema JSON is invalid: " + tool.name(),
                    HttpStatus.BAD_REQUEST,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    ex
            );
        }
    }

    private ModelGatewayConversationMessage toConversationMessage(ChatPromptMessage message) {
        if (message.role() == ChatMessageRole.USER) {
            return ModelGatewayConversationMessage.user(message.content());
        }
        return ModelGatewayConversationMessage.assistant(message.content());
    }

    /**
     * 校验当前阶段支持的模型配置范围。
     */
    private void validateModelConfig(PreparedModelConfig modelConfig, CredentialSource credentialSource) {
        if (modelConfig.modelType() != ModelType.CHAT) {
            throw new ModelGatewayInvocationException(
                    "MODEL_CONFIG_TYPE_INVALID",
                    "Current model config is not a chat model",
                    HttpStatus.BAD_REQUEST,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    null
            );
        }
        if (!modelConfig.provider().isOpenAiCompatibleRuntime()) {
            throw new ModelGatewayInvocationException(
                    "MODEL_PROVIDER_NOT_SUPPORTED",
                    "Current runtime only supports OpenAI-compatible providers",
                    HttpStatus.NOT_IMPLEMENTED,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    null
            );
        }
    }

    private CredentialSource resolveCredentialSource(PreparedModelConfig modelConfig) {
        return modelConfig.credentialSource();
    }

    private String resolveBaseUrl(PreparedModelConfig modelConfig) {
        if (StringUtils.hasText(modelConfig.baseUrl())) {
            return trimTrailingSlash(modelConfig.baseUrl());
        }
        String providerDefault = modelConfig.provider().defaultCompatibleBaseUrl();
        if (StringUtils.hasText(providerDefault)) {
            return trimTrailingSlash(providerDefault);
        }
        return trimTrailingSlash(modelGatewayProperties.getOpenaiBaseUrl());
    }

    private String resolveApiKey(PreparedModelConfig modelConfig, CredentialSource credentialSource) {
        if (StringUtils.hasText(modelConfig.credentialCiphertext())) {
            return aesCryptoService.decrypt(modelConfig.credentialCiphertext());
        }
        if (credentialSource == CredentialSource.USER) {
            throw new ModelGatewayInvocationException(
                    "CREDENTIAL_SECRET_MISSING",
                    "Credential secret is missing",
                    HttpStatus.BAD_REQUEST,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    null
            );
        }

        String platformKey = modelGatewayProperties.getPlatformOpenaiApiKey();
        if (!StringUtils.hasText(platformKey)) {
            throw new ModelGatewayInvocationException(
                    "PLATFORM_MODEL_KEY_NOT_CONFIGURED",
                    "Platform OpenAI key is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    null,
                    0L,
                    null
            );
        }
        return platformKey.trim();
    }

    private String trimTrailingSlash(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractAssistantContent(JsonNode message) {
        JsonNode contentNode = message.get("content");
        if (contentNode == null || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                if (part.hasNonNull("text")) {
                    parts.add(part.path("text").asText());
                } else if (part.hasNonNull("content")) {
                    parts.add(part.path("content").asText());
                }
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        return contentNode.toString();
    }

    private List<ModelGatewayToolCall> extractToolCalls(JsonNode message) {
        List<ModelGatewayToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : message.path("tool_calls")) {
            JsonNode functionNode = toolCallNode.path("function");
            toolCalls.add(new ModelGatewayToolCall(
                    toolCallNode.path("id").asText(),
                    functionNode.path("name").asText(),
                    functionNode.path("arguments").asText("{}")
            ));
        }
        return toolCalls;
    }

    private JsonNode readJson(String rawJson, PreparedModelConfig modelConfig, CredentialSource credentialSource) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (JsonProcessingException ex) {
            throw new ModelGatewayInvocationException(
                    "MODEL_RESPONSE_INVALID",
                    "Model response is not valid JSON",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    ex
            );
        }
    }

    private String toJson(JsonNode jsonNode, PreparedModelConfig modelConfig, CredentialSource credentialSource) {
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ex) {
            throw new ModelGatewayInvocationException(
                    "MODEL_REQUEST_INVALID",
                    "Failed to serialize model request",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    modelConfig.credentialId(),
                    0L,
                    ex
            );
        }
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return null;
        }
        return node.asInt();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String readFully(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String appendBodySnippet(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String normalized = body.trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }
        return " - " + normalized;
    }

    private static final class StreamingAccumulator {
        private final StringBuilder contentBuilder = new StringBuilder();
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;

        private String content() {
            return contentBuilder.toString();
        }
    }
}
