package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LlmNodeExecutor implements WorkflowNodeExecutor {

    private final ModelGatewayService modelGatewayService;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;

    public LlmNodeExecutor(
            ModelGatewayService modelGatewayService,
            ModelConfigRepository modelConfigRepository,
            ObjectMapper objectMapper
    ) {
        this.modelGatewayService = modelGatewayService;
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "LLM";
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        LlmNodeConfig nodeConfig = parseConfig(ctx.node().getConfigJson());
        String systemPrompt = getSystemPrompt(ctx);
        List<ModelGatewayConversationMessage> messages = buildMessages(ctx);
        PreparedModelConfig modelConfig = resolveModelConfig(ctx, nodeConfig);
        String sessionMemorySummary = stringContext(ctx, "session_memory_summary");
        String retrievalContext = stringContext(ctx, "retrieval_context");

        ModelGatewayChatCompletion completion = modelGatewayService.generateChatCompletion(
                modelConfig,
                systemPrompt,
                sessionMemorySummary,
                retrievalContext,
                messages,
                List.of()
        );

        return new NodeExecutionResult(null, Map.of(
                "llm_output", completion.content(),
                "llm_provider", completion.provider().name(),
                "llm_model", completion.modelName(),
                "llm_prompt_tokens", completion.promptTokens(),
                "llm_completion_tokens", completion.completionTokens()
        ));
    }

    private String getSystemPrompt(NodeExecutionContext ctx) {
        try {
            JsonNode nodeConfig = objectMapper.readTree(ctx.node().getConfigJson());
            if (nodeConfig.has("systemPrompt") && !nodeConfig.get("systemPrompt").isNull()) {
                String prompt = nodeConfig.get("systemPrompt").asText();
                if (!prompt.isBlank()) {
                    return prompt;
                }
            }
        } catch (Exception ignored) {
        }
        Object sp = ctx.context().get("system_prompt");
        return sp != null ? sp.toString() : "";
    }

    private LlmNodeConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, LlmNodeConfig.class);
        } catch (Exception ex) {
            throw new ApiException(
                    "WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid LLM node config JSON",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private PreparedModelConfig resolveModelConfig(NodeExecutionContext ctx, LlmNodeConfig nodeConfig) {
        PreparedModelConfig baseConfig = ctx.modelConfig();
        if (nodeConfig.modelConfigId() != null) {
            ModelConfig modelConfig = modelConfigRepository.findByIdAndTenant_Id(nodeConfig.modelConfigId(), ctx.tenantId())
                    .orElseThrow(() -> new ApiException(
                            "MODEL_CONFIG_NOT_FOUND",
                            "Workflow LLM node model config not found",
                            HttpStatus.BAD_REQUEST
                    ));
            if (!modelConfig.isEnabled() || modelConfig.getModelType() != ModelType.CHAT) {
                throw new ApiException(
                        "MODEL_CONFIG_INVALID",
                        "Workflow LLM node must use an enabled chat model",
                        HttpStatus.BAD_REQUEST
                );
            }
            baseConfig = snapshotModelConfig(modelConfig);
        }

        if (nodeConfig.temperature() == null) {
            return baseConfig;
        }

        return new PreparedModelConfig(
                baseConfig.provider(),
                baseConfig.modelType(),
                baseConfig.modelName(),
                baseConfig.credentialSource(),
                baseConfig.credentialId(),
                baseConfig.credentialCiphertext(),
                nodeConfig.temperature(),
                baseConfig.maxTokens(),
                baseConfig.officialModelConfigId(),
                baseConfig.baseUrl(),
                baseConfig.inputPricePerMillion(),
                baseConfig.outputPricePerMillion(),
                baseConfig.currency()
        );
    }

    private PreparedModelConfig snapshotModelConfig(ModelConfig modelConfig) {
        Credential credential = modelConfig.getCredential();
        CredentialSource credentialSource = resolveCredentialSource(modelConfig, credential);
        return new PreparedModelConfig(
                modelConfig.getProvider(),
                modelConfig.getModelType(),
                modelConfig.getModelName(),
                credentialSource,
                credential == null ? null : credential.getId(),
                credential == null ? null : credential.getSecretCiphertext(),
                modelConfig.getTemperature(),
                modelConfig.getMaxTokens(),
                null,
                credential == null ? null : credential.getBaseUrl(),
                null,
                null,
                null
        );
    }

    private CredentialSource resolveCredentialSource(ModelConfig modelConfig, Credential credential) {
        ModelCredentialSource source = modelConfig.getCredentialSource() != null
                ? modelConfig.getCredentialSource()
                : (credential == null ? ModelCredentialSource.PLATFORM : ModelCredentialSource.USER);
        if (source == ModelCredentialSource.USER) {
            if (credential == null || credential.getStatus() != CredentialStatus.ACTIVE) {
                throw new ApiException(
                        "MODEL_CREDENTIAL_DISABLED",
                        "Workflow LLM node model credential is unavailable",
                        HttpStatus.BAD_REQUEST
                );
            }
            return CredentialSource.USER;
        }
        return CredentialSource.PLATFORM;
    }

    private String stringContext(NodeExecutionContext ctx, String key) {
        Object value = ctx.context().get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private List<ModelGatewayConversationMessage> buildMessages(NodeExecutionContext ctx) {
        List<ModelGatewayConversationMessage> messages = new ArrayList<>();

        Object historyObj = ctx.context().get("history_messages");
        if (historyObj instanceof List<?> historyList) {
            for (Object item : historyList) {
                if (item instanceof ChatPromptMessage cpm) {
                    String role = cpm.role().name().equalsIgnoreCase("USER") ? "user" : "assistant";
                    messages.add(new ModelGatewayConversationMessage(role, cpm.content(), null, null));
                } else if (item instanceof Map<?, ?> raw) {
                    String role = String.valueOf(raw.get("role") != null ? raw.get("role") : "user");
                    String content = String.valueOf(raw.get("content") != null ? raw.get("content") : "");
                    messages.add(new ModelGatewayConversationMessage(role, content, null, null));
                }
            }
        }

        Object userInput = ctx.context().get("user_input");
        if (userInput != null) {
            messages.add(new ModelGatewayConversationMessage("user", userInput.toString(), null, null));
        }

        return messages;
    }

    private record LlmNodeConfig(
            Long modelConfigId,
            String systemPrompt,
            BigDecimal temperature
    ) {
    }
}
