package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelCallAccountingService;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.enjoy.agent.workflow.application.WorkflowExecutionBudget;
import com.enjoy.agent.workflow.application.WorkflowVariableResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LlmNodeExecutor implements WorkflowNodeExecutor {

    private static final int MAX_RESOLVED_SYSTEM_PROMPT_LENGTH = 20_000;

    private final ModelGatewayService modelGatewayService;
    private final ModelCallAccountingService modelCallAccountingService;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final BillingUsageApplicationService billingUsageApplicationService;

    public LlmNodeExecutor(
            ModelGatewayService modelGatewayService,
            ModelCallAccountingService modelCallAccountingService,
            ModelConfigRepository modelConfigRepository,
            ObjectMapper objectMapper,
            BillingUsageApplicationService billingUsageApplicationService
    ) {
        this.modelGatewayService = modelGatewayService;
        this.modelCallAccountingService = modelCallAccountingService;
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.billingUsageApplicationService = billingUsageApplicationService;
    }

    @Override
    public String nodeType() {
        return "LLM";
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        LlmNodeConfig nodeConfig = parseConfig(ctx.node().getConfigJson());
        String systemPrompt = getSystemPrompt(ctx, nodeConfig);
        List<ModelGatewayConversationMessage> messages = buildMessages(ctx);
        PreparedModelConfig modelConfig = resolveModelConfig(ctx, nodeConfig);
        if (ctx.executionBudget() != null && modelConfig.maxTokens() == null) {
            modelConfig = withMaxTokens(
                    modelConfig, ctx.executionBudget().resolveMaxOutputTokens(modelConfig.maxTokens()));
        }
        String sessionMemorySummary = stringContext(ctx, "session_memory_summary");
        String retrievalContext = stringContext(ctx, "retrieval_context");
        int inputTokenUpperBound = modelGatewayService.estimateInputTokenUpperBound(
                systemPrompt, sessionMemorySummary, retrievalContext, messages, List.of());

        PreparedChatTurn accountingTurn = accountingTurn(ctx.accountingTurn(), modelConfig);
        WorkflowExecutionBudget.ModelCallPermit budgetPermit = ctx.executionBudget() == null
                ? null
                : ctx.executionBudget().beforeModelCall(inputTokenUpperBound, modelConfig.maxTokens());
        Long reservationId = accountingTurn == null ? null
                : billingUsageApplicationService.beginOfficialModelCall(
                        ctx.userId(), modelConfig, inputTokenUpperBound);
        boolean accounted = false;
        boolean budgetRecorded = false;
        ModelGatewayChatCompletion completion;
        try {
            completion = modelGatewayService.generateChatCompletion(
                    modelConfig, systemPrompt, sessionMemorySummary, retrievalContext, messages, List.of());
            if (accountingTurn != null) {
                modelCallAccountingService.recordSuccess(accountingTurn, completion, reservationId);
                accounted = true;
            }
            if (ctx.executionBudget() != null) {
                ctx.executionBudget().recordModelUsage(budgetPermit, completion);
                budgetRecorded = true;
            }
        } finally {
            if (accountingTurn != null && !accounted) {
                billingUsageApplicationService.releaseOfficialModelCall(ctx.userId(), reservationId);
            }
            if (ctx.executionBudget() != null && !budgetRecorded) {
                ctx.executionBudget().releaseModelCall(budgetPermit);
            }
        }

        return new NodeExecutionResult(null, Map.of(
                "llm_output", completion.content(),
                "llm_provider", completion.provider().name(),
                "llm_model", completion.modelName(),
                "llm_prompt_tokens", completion.promptTokens() == null ? 0 : completion.promptTokens(),
                "llm_completion_tokens", completion.completionTokens() == null ? 0 : completion.completionTokens()
        ));
    }

    private String getSystemPrompt(NodeExecutionContext ctx, LlmNodeConfig nodeConfig) {
        String configured = nodeConfig.systemPrompt();
        Object fallback = ctx.context().get("system_prompt");
        String prompt = configured != null && !configured.isBlank()
                ? configured : fallback == null ? "" : fallback.toString();
        return resolveSystemPromptTemplates(prompt, ctx.context());
    }

    private String resolveSystemPromptTemplates(String prompt, Map<String, Object> context) {
        return WorkflowVariableResolver.resolveTextTemplate(
                prompt, context, objectMapper, MAX_RESOLVED_SYSTEM_PROMPT_LENGTH, this::promptTooLong);
    }

    private ApiException promptTooLong() {
        return new ApiException(
                "WORKFLOW_SYSTEM_PROMPT_TOO_LONG",
                "Resolved workflow LLM system prompt must be at most "
                        + MAX_RESOLVED_SYSTEM_PROMPT_LENGTH + " characters",
                HttpStatus.BAD_REQUEST
        );
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
            ModelCredentialSource source = modelConfig.getCredentialSource() != null
                    ? modelConfig.getCredentialSource()
                    : (modelConfig.getCredential() == null
                            ? ModelCredentialSource.PLATFORM : ModelCredentialSource.USER);
            if (source == ModelCredentialSource.PLATFORM) {
                throw new ApiException(
                        "WORKFLOW_NODE_PLATFORM_MODEL_UNSUPPORTED",
                        "A workflow node cannot select a platform model without an official billable model binding",
                        HttpStatus.FORBIDDEN
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

    private PreparedChatTurn accountingTurn(PreparedChatTurn source, PreparedModelConfig modelConfig) {
        if (source == null) {
            return null;
        }
        return new PreparedChatTurn(
                source.tenantId(),
                source.userId(),
                source.agentId(),
                source.sessionId(),
                source.userMessageId(),
                source.latestUserMessageContent(),
                source.systemPrompt(),
                source.sessionMemory(),
                modelConfig,
                source.knowledgeBase(),
                source.mcpTools(),
                source.retrievalContext(),
                source.retrievalDebug(),
                source.historyMessages(),
                source.workflowId(),
                source.mcpToolApprovalTokens()
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

    private PreparedModelConfig withMaxTokens(PreparedModelConfig config, int maxTokens) {
        return new PreparedModelConfig(
                config.provider(), config.modelType(), config.modelName(), config.credentialSource(),
                config.credentialId(), config.credentialCiphertext(), config.temperature(), maxTokens,
                config.officialModelConfigId(), config.baseUrl(), config.inputPricePerMillion(),
                config.outputPricePerMillion(), config.currency()
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
