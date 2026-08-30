package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 基于 Spring AI 的运行时模型网关。
 *
 * <p>平台仍然在每次调用时根据租户模型快照构造 ChatModel，避免把任一用户的
 * API Key 或 Base URL 放进全局 {@code spring.ai.*} 配置。这样既能使用 Spring AI
 * 的 OpenAI 协议、SSE 和 usage 映射，又能保留现有的凭据隔离、异常、日志和计费契约。</p>
 */
@Service
public class ModelGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayService.class);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final String SESSION_MEMORY_PREFIX = "以下是当前会话的长期摘要记忆，请保持对话连续性，并据此补足窗口外但仍然重要的上下文。\n\n";
    private static final String RETRIEVAL_CONTEXT_PREFIX = "以下是与当前问题相关的知识库内容，请优先依据这些内容回答；如果知识库内容不足以回答，请明确说明并谨慎补充。\n\n";

    private final AesCryptoService aesCryptoService;
    private final ModelGatewayProperties modelGatewayProperties;
    private final ObjectMapper objectMapper;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final JdkClientHttpRequestFactory requestFactory;
    private final JdkClientHttpConnector reactiveConnector;
    private final ModelPromptBudgetEstimator promptBudgetEstimator;

    public ModelGatewayService(
            AesCryptoService aesCryptoService,
            ModelGatewayProperties modelGatewayProperties,
            ObjectMapper objectMapper,
            OutboundUrlPolicy outboundUrlPolicy
    ) {
        this.aesCryptoService = aesCryptoService;
        this.modelGatewayProperties = modelGatewayProperties;
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.promptBudgetEstimator = new ModelPromptBudgetEstimator(modelGatewayProperties);
        HttpClient delegate = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(modelGatewayProperties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpClient boundedClient = new BoundedModelHttpClient(
                delegate, modelGatewayProperties.getMaxResponseBytes());
        this.requestFactory = new JdkClientHttpRequestFactory(boundedClient);
        this.requestFactory.setReadTimeout(Duration.ofSeconds(modelGatewayProperties.getReadTimeoutSeconds()));
        this.reactiveConnector = new JdkClientHttpConnector(boundedClient);
        this.reactiveConnector.setReadTimeout(Duration.ofSeconds(modelGatewayProperties.getReadTimeoutSeconds()));
    }

    /**
     * 调用模型生成一条普通文本回复。
     */
    public ModelGatewayResult generateReply(PreparedChatTurn preparedChatTurn) {
        List<ModelGatewayConversationMessage> conversationMessages = preparedChatTurn.historyMessages()
                .stream()
                .map(this::toConversationMessage)
                .toList();
        ModelGatewayChatCompletion completion = generateChatCompletionInternal(
                preparedChatTurn.modelConfig(),
                preparedChatTurn.systemPrompt(),
                preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                preparedChatTurn.retrievalContext(),
                conversationMessages,
                List.of()
        );
        rejectUnexpectedToolCalls(completion);
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
        ModelGatewayChatCompletion completion = generateChatCompletionInternal(
                modelConfig,
                systemPrompt,
                null,
                null,
                List.of(ModelGatewayConversationMessage.user(userPrompt)),
                List.of()
        );
        rejectUnexpectedToolCalls(completion);
        if (!StringUtils.hasText(completion.content())) {
            throw invocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    resolveCredentialSource(modelConfig),
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
     *
     * <p>工具执行仍由调用方控制，因此这里仅使用 ToolCallback 提供工具定义，并关闭
     * ChatModel 内部的自动执行。</p>
     */
    public ModelGatewayChatCompletion generateChatCompletion(
            PreparedChatTurn preparedChatTurn,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return generateChatCompletionInternal(
                preparedChatTurn.modelConfig(),
                preparedChatTurn.systemPrompt(),
                preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                preparedChatTurn.retrievalContext(),
                conversationMessages,
                tools
        );
    }

    /**
     * 以独立参数调用聊天补全，供工作流节点直接使用。
     */
    public ModelGatewayChatCompletion generateChatCompletion(
            PreparedModelConfig modelConfig,
            String systemPrompt,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return generateChatCompletionInternal(modelConfig, systemPrompt, null, null, conversationMessages, tools);
    }

    public int estimateInputTokenUpperBound(PreparedChatTurn turn) {
        List<ModelGatewayConversationMessage> messages = turn.historyMessages().stream()
                .map(this::toConversationMessage)
                .toList();
        return estimateInputTokenUpperBound(
                turn.systemPrompt(),
                turn.sessionMemory() == null ? null : turn.sessionMemory().summary(),
                turn.retrievalContext(),
                messages,
                List.of()
        );
    }

    public int estimateInputTokenUpperBound(
            PreparedChatTurn turn,
            List<ModelGatewayConversationMessage> messages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return estimateInputTokenUpperBound(
                turn.systemPrompt(),
                turn.sessionMemory() == null ? null : turn.sessionMemory().summary(),
                turn.retrievalContext(),
                messages,
                tools
        );
    }

    public int estimateInputTokenUpperBound(
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> messages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return promptBudgetEstimator.estimate(
                systemPrompt, sessionMemorySummary, retrievalContext, messages, tools)
                .conservativeInputTokenUpperBound();
    }

    public ModelGatewayChatCompletion generateChatCompletion(
            PreparedModelConfig modelConfig,
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        return generateChatCompletionInternal(
                modelConfig,
                systemPrompt,
                sessionMemorySummary,
                retrievalContext,
                conversationMessages,
                tools
        );
    }

    private ModelGatewayChatCompletion generateChatCompletionInternal(
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
            requireNotCancelled(modelConfig, credentialSource, startNanos);
            validateModelConfig(modelConfig, credentialSource);
            promptBudgetEstimator.estimate(
                    systemPrompt, sessionMemorySummary, retrievalContext, conversationMessages, tools);
            validateOutboundBaseUrl(resolvedBaseUrl, modelConfig, credentialSource);
            OpenAiChatModel chatModel = createChatModel(modelConfig, credentialSource, resolvedBaseUrl, false);
            Prompt prompt = buildPrompt(
                    modelConfig,
                    credentialSource,
                    systemPrompt,
                    sessionMemorySummary,
                    retrievalContext,
                    conversationMessages,
                    tools
            );
            ChatResponse response = callWithDeadline(() -> chatModel.call(prompt));
            return toCompletion(response, modelConfig, credentialSource, elapsedMillis(startNanos));
        } catch (ModelGatewayInvocationException ex) {
            throw ex;
        } catch (ApiException ex) {
            throw invocationException(ex.getCode(), ex.getMessage(), ex.getStatus(),
                    modelConfig, credentialSource, elapsedMillis(startNanos), ex);
        } catch (Exception ex) {
            if (isCancellation(ex)) {
                throw cancelled(modelConfig, credentialSource, startNanos, ex);
            }
            if (hasCause(ex, ModelResponseLimitException.class)) {
                throw responseTooLarge(modelConfig, credentialSource, startNanos, ex);
            }
            if (hasCause(ex, HttpTimeoutException.class)) {
                throw invocationException("MODEL_INVOCATION_TIMEOUT", "Model invocation timed out",
                        HttpStatus.GATEWAY_TIMEOUT, modelConfig, credentialSource, elapsedMillis(startNanos), ex);
            }
            log.error(
                    "Spring AI model call failed, provider={}, modelName={}, credentialSource={}, errorType={}",
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    ex.getClass().getSimpleName()
            );
            throw invocationException(
                    "MODEL_INVOCATION_FAILED",
                    "Model invocation failed",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    credentialSource,
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
            requireNotCancelled(modelConfig, credentialSource, startNanos);
            validateModelConfig(modelConfig, credentialSource);
            promptBudgetEstimator.estimate(
                    preparedChatTurn.systemPrompt(),
                    preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                    preparedChatTurn.retrievalContext(),
                    conversationMessages,
                    List.of());
            validateOutboundBaseUrl(resolvedBaseUrl, modelConfig, credentialSource);
            OpenAiChatModel chatModel = createChatModel(modelConfig, credentialSource, resolvedBaseUrl, true);
            Prompt prompt = buildPrompt(
                    modelConfig,
                    credentialSource,
                    preparedChatTurn.systemPrompt(),
                    preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                    preparedChatTurn.retrievalContext(),
                    conversationMessages,
                    List.of()
            );
            StreamingAccumulator accumulator = new StreamingAccumulator();

            chatModel.stream(prompt)
                    .doOnNext(response -> consumeStreamingResponse(response, accumulator, deltaConsumer))
                    .blockLast(Duration.ofSeconds(modelGatewayProperties.getReadTimeoutSeconds()));

            if (!StringUtils.hasText(accumulator.content())) {
                throw invocationException(
                        "MODEL_EMPTY_RESPONSE",
                        "Model returned empty response",
                        HttpStatus.BAD_GATEWAY,
                        modelConfig,
                        credentialSource,
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
        } catch (ModelGatewayStreamConsumerException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            ModelGatewayStreamConsumerException consumerException = findStreamConsumerException(ex);
            if (consumerException != null) {
                throw consumerException;
            }
            if (ex instanceof ModelGatewayInvocationException invocationException) {
                throw invocationException;
            }
            if (ex instanceof ApiException apiException) {
                throw invocationException(apiException.getCode(), apiException.getMessage(), apiException.getStatus(),
                        modelConfig, credentialSource, elapsedMillis(startNanos), apiException);
            }
            if (isCancellation(ex)) {
                throw cancelled(modelConfig, credentialSource, startNanos, ex);
            }
            if (hasCause(ex, ModelResponseLimitException.class)) {
                throw responseTooLarge(modelConfig, credentialSource, startNanos, ex);
            }
            if (hasCause(ex, HttpTimeoutException.class)
                    || ex instanceof IllegalStateException && ex.getMessage() != null
                    && ex.getMessage().contains("Timeout on blocking read")) {
                throw invocationException("MODEL_INVOCATION_TIMEOUT", "Model invocation timed out",
                        HttpStatus.GATEWAY_TIMEOUT, modelConfig, credentialSource, elapsedMillis(startNanos), ex);
            }
            log.error(
                    "Spring AI streaming model call failed, provider={}, modelName={}, credentialSource={}, errorType={}",
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    ex.getClass().getSimpleName()
            );
            throw invocationException(
                    "MODEL_INVOCATION_FAILED",
                    "Model invocation failed",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    credentialSource,
                    elapsedMillis(startNanos),
                    ex
            );
        } catch (Exception ex) {
            if (isCancellation(ex)) {
                throw cancelled(modelConfig, credentialSource, startNanos, ex);
            }
            if (hasCause(ex, ModelResponseLimitException.class)) {
                throw responseTooLarge(modelConfig, credentialSource, startNanos, ex);
            }
            log.error(
                    "Spring AI streaming model call failed, provider={}, modelName={}, credentialSource={}, errorType={}",
                    modelConfig.provider(),
                    modelConfig.modelName(),
                    credentialSource,
                    ex.getClass().getSimpleName()
            );
            throw invocationException(
                    "MODEL_INVOCATION_FAILED",
                    "Model invocation failed",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    credentialSource,
                    elapsedMillis(startNanos),
                    ex
            );
        }
    }

    private OpenAiChatModel createChatModel(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            String resolvedBaseUrl,
            boolean streamUsage
    ) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(resolvedBaseUrl)
                .apiKey(resolveApiKey(modelConfig, credentialSource))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(reactiveConnector)
                        .exchangeStrategies(ExchangeStrategies.builder()
                                .codecs(codecs -> codecs.defaultCodecs()
                                        .maxInMemorySize(modelGatewayProperties.getMaxResponseBytes()))
                                .build()))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(buildDefaultOptions(modelConfig, streamUsage))
                .build();
    }

    private OpenAiChatOptions buildDefaultOptions(PreparedModelConfig modelConfig, boolean streamUsage) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(modelConfig.modelName());
        if (streamUsage) {
            builder.streamUsage(true);
        }
        BigDecimal temperature = modelConfig.temperature();
        if (temperature != null) {
            builder.temperature(temperature.doubleValue());
        }
        if (modelConfig.maxTokens() != null) {
            builder.maxTokens(modelConfig.maxTokens());
        }
        return builder.build();
    }

    private Prompt buildPrompt(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> tools
    ) {
        List<Message> instructions = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            instructions.add(new SystemMessage(systemPrompt));
        }
        if (StringUtils.hasText(sessionMemorySummary)) {
            instructions.add(new SystemMessage(SESSION_MEMORY_PREFIX + sessionMemorySummary));
        }
        if (StringUtils.hasText(retrievalContext)) {
            instructions.add(new SystemMessage(RETRIEVAL_CONTEXT_PREFIX + retrievalContext));
        }
        instructions.addAll(toSpringAiMessages(conversationMessages));

        if (tools == null || tools.isEmpty()) {
            return new Prompt(instructions);
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(toToolCallbacks(tools, modelConfig, credentialSource))
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(instructions, options);
    }

    private List<Message> toSpringAiMessages(List<ModelGatewayConversationMessage> conversationMessages) {
        List<Message> messages = new ArrayList<>();
        Map<String, String> toolNamesByCallId = new HashMap<>();
        for (ModelGatewayConversationMessage conversationMessage : conversationMessages) {
            String role = conversationMessage.role();
            if ("user".equals(role)) {
                messages.add(new UserMessage(conversationMessage.content() == null ? "" : conversationMessage.content()));
                continue;
            }
            if ("assistant".equals(role)) {
                if (conversationMessage.toolCalls() == null || conversationMessage.toolCalls().isEmpty()) {
                    messages.add(new AssistantMessage(conversationMessage.content() == null ? "" : conversationMessage.content()));
                    continue;
                }
                List<AssistantMessage.ToolCall> toolCalls = conversationMessage.toolCalls().stream()
                        .map(toolCall -> {
                            toolNamesByCallId.put(toolCall.id(), toolCall.name());
                            return new AssistantMessage.ToolCall(
                                    toolCall.id(),
                                    "function",
                                    toolCall.name(),
                                    toolCall.argumentsJson()
                            );
                        })
                        .toList();
                messages.add(AssistantMessage.builder()
                        .content(conversationMessage.content())
                        .toolCalls(toolCalls)
                        .build());
                continue;
            }
            if ("tool".equals(role)) {
                String toolName = toolNamesByCallId.getOrDefault(conversationMessage.toolCallId(), "tool");
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                conversationMessage.toolCallId(),
                                toolName,
                                conversationMessage.content() == null ? "" : conversationMessage.content()
                        )))
                        .build());
                continue;
            }
            throw new IllegalArgumentException("Unsupported model conversation role: " + role);
        }
        return messages;
    }

    private List<ToolCallback> toToolCallbacks(
            List<ModelGatewayToolDefinition> tools,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource
    ) {
        return tools.stream()
                .map(tool -> new DefinitionOnlyToolCallback(
                        tool.name(),
                        tool.description(),
                        parseSchema(tool, modelConfig, credentialSource)
                ))
                .map(ToolCallback.class::cast)
                .toList();
    }

    private String parseSchema(
            ModelGatewayToolDefinition tool,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource
    ) {
        if (!StringUtils.hasText(tool.inputSchemaJson())) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            objectMapper.readTree(tool.inputSchemaJson());
            return tool.inputSchemaJson();
        } catch (JsonProcessingException ex) {
            throw invocationException(
                    "MODEL_TOOL_SCHEMA_INVALID",
                    "Tool schema JSON is invalid: " + tool.name(),
                    HttpStatus.BAD_REQUEST,
                    modelConfig,
                    credentialSource,
                    0L,
                    ex
            );
        }
    }

    private ModelGatewayChatCompletion toCompletion(
            ChatResponse response,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            long latencyMs
    ) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw invocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    credentialSource,
                    latencyMs,
                    null
            );
        }
        AssistantMessage output = response.getResult().getOutput();
        String content = output == null ? null : output.getText();
        List<ModelGatewayToolCall> toolCalls = output == null || output.getToolCalls() == null
                ? List.of()
                : output.getToolCalls().stream()
                .map(toolCall -> new ModelGatewayToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()))
                .toList();
        requireResponseWithinCharLimit(content, toolCalls);
        if (!StringUtils.hasText(content) && toolCalls.isEmpty()) {
            throw invocationException(
                    "MODEL_EMPTY_RESPONSE",
                    "Model returned empty response",
                    HttpStatus.BAD_GATEWAY,
                    modelConfig,
                    credentialSource,
                    latencyMs,
                    null
            );
        }

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new ModelGatewayChatCompletion(
                StringUtils.hasText(content) ? content.trim() : null,
                toolCalls,
                modelConfig.provider(),
                modelConfig.modelName(),
                credentialSource,
                modelConfig.credentialId(),
                latencyMs,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens()
        );
    }

    private void consumeStreamingResponse(
            ChatResponse response,
            StreamingAccumulator accumulator,
            Consumer<String> deltaConsumer
    ) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Model stream was cancelled");
        }
        copyUsage(response, accumulator);
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return;
        }
        AssistantMessage output = response.getResult().getOutput();
        String delta = output == null ? null : output.getText();
        if (!StringUtils.hasText(delta)) {
            return;
        }
        if ((long) accumulator.contentBuilder.length() + delta.length()
                > modelGatewayProperties.getMaxResponseChars()) {
            throw new ModelResponseLimitException(modelGatewayProperties.getMaxResponseChars());
        }
        accumulator.contentBuilder.append(delta);
        deltaConsumer.accept(delta);
    }

    private void copyUsage(ChatResponse response, StreamingAccumulator accumulator) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage.getPromptTokens() != null) {
            accumulator.promptTokens = usage.getPromptTokens();
        }
        if (usage.getCompletionTokens() != null) {
            accumulator.completionTokens = usage.getCompletionTokens();
        }
        if (usage.getTotalTokens() != null) {
            accumulator.totalTokens = usage.getTotalTokens();
        }
    }

    private void rejectUnexpectedToolCalls(ModelGatewayChatCompletion completion) {
        if (completion.toolCalls().isEmpty()) {
            return;
        }
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
            throw invocationException(
                    "MODEL_CONFIG_TYPE_INVALID",
                    "Current model config is not a chat model",
                    HttpStatus.BAD_REQUEST,
                    modelConfig,
                    credentialSource,
                    0L,
                    null
            );
        }
        if (!modelConfig.provider().isOpenAiCompatibleRuntime()) {
            throw invocationException(
                    "MODEL_PROVIDER_NOT_SUPPORTED",
                    "Current runtime only supports OpenAI-compatible providers",
                    HttpStatus.NOT_IMPLEMENTED,
                    modelConfig,
                    credentialSource,
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
            return ModelGatewayProperties.normalizeOpenaiBaseUrl(modelConfig.baseUrl());
        }

        String configuredBaseUrl = modelGatewayProperties.getOpenaiBaseUrl();
        if (!DEFAULT_OPENAI_BASE_URL.equals(configuredBaseUrl)) {
            return configuredBaseUrl;
        }

        String providerDefault = modelConfig.provider().defaultCompatibleBaseUrl();
        if (StringUtils.hasText(providerDefault)) {
            return ModelGatewayProperties.normalizeOpenaiBaseUrl(providerDefault);
        }
        return configuredBaseUrl;
    }

    private void validateOutboundBaseUrl(
            String baseUrl,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource
    ) {
        try {
            URI ignored = outboundUrlPolicy.requireAllowedHttpUrl(baseUrl, "MODEL_BASE_URL_FORBIDDEN");
        } catch (ApiException ex) {
            throw invocationException(ex.getCode(), ex.getMessage(), ex.getStatus(),
                    modelConfig, credentialSource, 0L, ex);
        }
    }

    private void requireResponseWithinCharLimit(
            String content,
            List<ModelGatewayToolCall> toolCalls
    ) {
        long chars = content == null ? 0 : content.length();
        for (ModelGatewayToolCall toolCall : toolCalls) {
            chars += toolCall.argumentsJson() == null ? 0 : toolCall.argumentsJson().length();
            if (chars > modelGatewayProperties.getMaxResponseChars()) {
                throw new ModelResponseLimitException(modelGatewayProperties.getMaxResponseChars());
            }
        }
        if (chars > modelGatewayProperties.getMaxResponseChars()) {
            throw new ModelResponseLimitException(modelGatewayProperties.getMaxResponseChars());
        }
    }

    private ModelGatewayInvocationException responseTooLarge(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            long startNanos,
            Throwable cause
    ) {
        return invocationException(
                "MODEL_RESPONSE_TOO_LARGE",
                "Model response exceeded the configured limit",
                HttpStatus.BAD_GATEWAY,
                modelConfig,
                credentialSource,
                elapsedMillis(startNanos),
                cause
        );
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isCancellation(Throwable throwable) {
        return Thread.currentThread().isInterrupted()
                || hasCause(throwable, InterruptedException.class)
                || hasCause(throwable, CancellationException.class);
    }

    private <T> T callWithDeadline(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread worker = Thread.ofVirtual().name("model-gateway-sync").start(task);
        try {
            return task.get(modelGatewayProperties.getReadTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            worker.interrupt();
            throw new HttpTimeoutException("Model invocation exceeded its total deadline");
        } catch (InterruptedException ex) {
            task.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Model invocation failed", cause);
        }
    }

    private void requireNotCancelled(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            long startNanos
    ) {
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled(modelConfig, credentialSource, startNanos, null);
        }
    }

    private ModelGatewayInvocationException cancelled(
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            long startNanos,
            Throwable cause
    ) {
        return invocationException(
                "MODEL_INVOCATION_CANCELLED",
                "Model invocation was cancelled",
                HttpStatus.REQUEST_TIMEOUT,
                modelConfig,
                credentialSource,
                elapsedMillis(startNanos),
                cause
        );
    }

    private String resolveApiKey(PreparedModelConfig modelConfig, CredentialSource credentialSource) {
        if (StringUtils.hasText(modelConfig.credentialCiphertext())) {
            return aesCryptoService.decrypt(modelConfig.credentialCiphertext());
        }
        if (credentialSource == CredentialSource.USER) {
            throw invocationException(
                    "CREDENTIAL_SECRET_MISSING",
                    "Credential secret is missing",
                    HttpStatus.BAD_REQUEST,
                    modelConfig,
                    credentialSource,
                    0L,
                    null
            );
        }

        String platformKey = modelGatewayProperties.getPlatformOpenaiApiKey();
        if (!StringUtils.hasText(platformKey)) {
            throw invocationException(
                    "PLATFORM_MODEL_KEY_NOT_CONFIGURED",
                    "Platform OpenAI key is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    modelConfig,
                    credentialSource,
                    0L,
                    null
            );
        }
        return platformKey.trim();
    }

    private ModelGatewayInvocationException invocationException(
            String code,
            String message,
            HttpStatus status,
            PreparedModelConfig modelConfig,
            CredentialSource credentialSource,
            long latencyMs,
            Throwable cause
    ) {
        return new ModelGatewayInvocationException(
                code,
                message,
                status,
                modelConfig.provider(),
                modelConfig.modelName(),
                credentialSource,
                modelConfig.credentialId(),
                latencyMs,
                cause
        );
    }

    private ModelGatewayStreamConsumerException findStreamConsumerException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayStreamConsumerException consumerException) {
                return consumerException;
            }
            current = current.getCause();
        }
        return null;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final ToolDefinition definition;

        private DefinitionOnlyToolCallback(String name, String description, String inputSchema) {
            this.definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(description == null ? "" : description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException("Tool execution is controlled by the application orchestrator");
        }
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
