package com.enjoy.agent.chat.application;

import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpHighRiskApprovalService;
import com.enjoy.agent.mcp.application.McpRuntimeProperties;
import com.enjoy.agent.mcp.application.McpToolCallFailure;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.modelgateway.application.ModelCallAccountingService;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolCall;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolDefinition;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 处理带 MCP 工具调用的聊天编排。
 */
@Service
public class McpChatOrchestratorService {

    private final ModelGatewayService modelGatewayService;
    private final com.enjoy.agent.mcp.application.McpActionConfirmationService actionConfirmations;
    private final ModelCallAccountingService modelCallAccountingService;
    private final McpGatewayService mcpGatewayService;
    private final McpToolCallLogService mcpToolCallLogService;
    private final McpHighRiskApprovalService approvalService;
    private final McpRuntimeProperties mcpRuntimeProperties;
    private final ObjectMapper objectMapper;
    private final BillingUsageApplicationService billingUsageApplicationService;

    public McpChatOrchestratorService(
            ModelGatewayService modelGatewayService,
            ModelCallAccountingService modelCallAccountingService,
            McpGatewayService mcpGatewayService,
            McpToolCallLogService mcpToolCallLogService,
            McpHighRiskApprovalService approvalService,
            McpRuntimeProperties mcpRuntimeProperties,
            ObjectMapper objectMapper,
            BillingUsageApplicationService billingUsageApplicationService,
            com.enjoy.agent.mcp.application.McpActionConfirmationService actionConfirmations
    ) {
        this.actionConfirmations = actionConfirmations;
        this.modelGatewayService = modelGatewayService;
        this.modelCallAccountingService = modelCallAccountingService;
        this.mcpGatewayService = mcpGatewayService;
        this.mcpToolCallLogService = mcpToolCallLogService;
        this.approvalService = approvalService;
        this.mcpRuntimeProperties = mcpRuntimeProperties;
        this.objectMapper = objectMapper;
        this.billingUsageApplicationService = billingUsageApplicationService;
    }

    /**
     * 在一个聊天轮次内执行模型与工具的往返调用，直到拿到最终文本回复。
     */
    public ModelGatewayResult completeTurn(PreparedChatTurn preparedChatTurn) {
        try (McpGatewayService.McpTurnSession gatewaySession = mcpGatewayService.openTurnSession()) {
            return completeTurn(preparedChatTurn, gatewaySession);
        }
    }

    private ModelGatewayResult completeTurn(
            PreparedChatTurn preparedChatTurn,
            McpGatewayService.McpTurnSession gatewaySession
    ) {
        preparedChatTurn = withBoundedMaxOutputTokens(preparedChatTurn);
        Map<String, PreparedMcpTool> toolsByName = indexToolsByUniqueName(preparedChatTurn.mcpTools());
        List<ModelGatewayToolDefinition> toolDefinitions = toolsByName.values().stream()
                .map(tool -> new ModelGatewayToolDefinition(tool.name(), tool.description(), tool.inputSchemaJson()))
                .toList();
        List<ModelGatewayConversationMessage> conversationMessages = preparedChatTurn.historyMessages().stream()
                .map(message -> message.role() == com.enjoy.agent.chat.domain.enums.ChatMessageRole.USER
                        ? ModelGatewayConversationMessage.user(message.content())
                        : ModelGatewayConversationMessage.assistant(message.content()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        int maxToolRounds = mcpRuntimeProperties.getMaxToolRounds();
        McpModelTokenBudget modelTokenBudget = new McpModelTokenBudget(
                mcpRuntimeProperties.getMaxCumulativeModelTokensPerTurn());
        for (int round = 0; round <= maxToolRounds; round++) {
            List<ModelGatewayToolDefinition> availableTools = round == maxToolRounds
                    ? List.of()
                    : toolDefinitions;
            com.enjoy.agent.shared.runtime.RuntimeEvents.emit("model_started", Map.of("round", round));
            ModelGatewayChatCompletion completion = invokeModel(
                    preparedChatTurn,
                    conversationMessages,
                    availableTools,
                    modelTokenBudget
            );
            com.enjoy.agent.shared.runtime.RuntimeEvents.emit("model_completed", Map.of("round", round));
            if (completion.toolCalls().isEmpty()) {
                return completion.toResult();
            }

            // 最后一轮已不再向模型暴露工具；若兼容服务仍返回 tool_calls，也禁止执行副作用。
            if (round == maxToolRounds) {
                break;
            }

            conversationMessages.add(ModelGatewayConversationMessage.assistantToolCalls(completion.toolCalls()));
            for (ModelGatewayToolCall toolCall : completion.toolCalls()) {
                String toolMessage = executeTool(preparedChatTurn, toolsByName, toolCall, gatewaySession);
                conversationMessages.add(ModelGatewayConversationMessage.tool(toolCall.id(), toolMessage));
            }
        }

        throw new ApiException(
                "MODEL_TOOL_LOOP_EXCEEDED",
                "Model exceeded maximum MCP tool calling rounds",
                HttpStatus.BAD_GATEWAY
        );
    }

    private ModelGatewayChatCompletion invokeModel(
            PreparedChatTurn preparedChatTurn,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> toolDefinitions,
            McpModelTokenBudget modelTokenBudget
    ) {
        int inputTokenUpperBound = modelGatewayService.estimateInputTokenUpperBound(
                preparedChatTurn, conversationMessages, toolDefinitions);
        McpModelTokenBudget.Permit tokenPermit = modelTokenBudget.beforeCall(
                inputTokenUpperBound, preparedChatTurn.modelConfig().maxTokens());
        Long reservationId = billingUsageApplicationService.beginOfficialModelCall(
                preparedChatTurn.userId(),
                preparedChatTurn.modelConfig(),
                inputTokenUpperBound);
        boolean accounted = false;
        boolean tokenBudgetSettled = false;
        try {
            ModelGatewayChatCompletion completion = modelGatewayService.generateChatCompletion(
                    preparedChatTurn,
                    conversationMessages,
                    toolDefinitions
            );
            modelCallAccountingService.recordSuccess(preparedChatTurn, completion, reservationId);
            accounted = true;
            modelTokenBudget.record(tokenPermit, completion);
            tokenBudgetSettled = true;
            return completion;
        } catch (ModelGatewayInvocationException ex) {
            throw ex;
        } finally {
            if (!accounted) {
                billingUsageApplicationService.releaseOfficialModelCall(
                        preparedChatTurn.userId(), reservationId);
            }
            if (!tokenBudgetSettled) {
                modelTokenBudget.release(tokenPermit);
            }
        }
    }

    private String executeTool(
            PreparedChatTurn preparedChatTurn,
            Map<String, PreparedMcpTool> toolsByName,
            ModelGatewayToolCall toolCall,
            McpGatewayService.McpTurnSession gatewaySession
    ) {
        PreparedMcpTool tool = toolsByName.get(toolCall.name());
        String requestPayload = toolCall.argumentsJson();
        long startNanos = System.nanoTime();

        if (tool == null) {
            McpToolCallFailure failure = new McpToolCallFailure(
                    "MCP_TOOL_NOT_BOUND",
                    "Requested MCP tool is not bound to current agent: " + toolCall.name(),
                    elapsedMillis(startNanos),
                    null
            );
            return "Tool call failed: " + failure.getCode() + " - " + failure.getMessage();
        }

        McpToolCallResult result;
        try {
            Map<String, Object> arguments = parseArguments(toolCall.argumentsJson());
            com.enjoy.agent.shared.runtime.RuntimeEvents.emit("tool_started", Map.of("toolName", tool.name(), "toolId", tool.id()));
            result = tool.riskLevel() == com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel.HIGH
                    ? actionConfirmations.requireResult(preparedChatTurn.tenantId(), preparedChatTurn.userId(),
                        preparedChatTurn.agentId(), preparedChatTurn.sessionId(), "chat:" + preparedChatTurn.userMessageId(), tool, arguments)
                    : gatewaySession.callTool(tool, arguments);
        } catch (RuntimeException ex) {
            if (ex instanceof ApiException api && "MCP_ACTION_CONFIRMATION_REQUIRED".equals(api.getCode())) throw api;
            com.enjoy.agent.shared.runtime.RuntimeEvents.emit("tool_failed", Map.of("toolName", tool.name()));
            String failureCode = ex instanceof ApiException apiException
                    ? apiException.getCode()
                    : "MCP_TOOL_INVOCATION_FAILED";
            McpToolCallFailure failure = new McpToolCallFailure(
                    failureCode,
                    ex.getMessage() == null ? "MCP tool invocation failed" : ex.getMessage(),
                    elapsedMillis(startNanos),
                    ex
            );
            mcpToolCallLogService.recordFailure(preparedChatTurn, tool, toolCall.id(), requestPayload, failure);
            return "Tool call failed: " + failure.getCode() + " - " + failure.getMessage();
        }

        long latencyMs = elapsedMillis(startNanos);
        com.enjoy.agent.shared.runtime.RuntimeEvents.emit("tool_completed", Map.of("toolName", tool.name(), "latencyMs", latencyMs, "error", result.error()));
        if (result.error()) {
            McpToolCallFailure failure = new McpToolCallFailure(
                    "MCP_TOOL_REPORTED_ERROR",
                    StringUtils.hasText(result.modelVisibleContent())
                            ? result.modelVisibleContent()
                            : "MCP server reported a tool execution error",
                    latencyMs,
                    null
            );
            mcpToolCallLogService.recordFailure(
                    preparedChatTurn,
                    tool,
                    toolCall.id(),
                    requestPayload,
                    result.rawResponsePayload(),
                    failure
            );
            return truncateForModel(
                    "Tool call failed: " + failure.getCode() + " - " + failure.getMessage()
            );
        }
        mcpToolCallLogService.recordSuccess(preparedChatTurn, tool, toolCall.id(), requestPayload, result, latencyMs);
        return truncateForModel(result.modelVisibleContent());
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new ApiException("MCP_TOOL_ARGUMENTS_INVALID", "Model returned invalid MCP tool arguments", HttpStatus.BAD_GATEWAY);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private PreparedChatTurn withBoundedMaxOutputTokens(PreparedChatTurn turn) {
        if (turn.modelConfig().maxTokens() != null) {
            return turn;
        }
        var config = turn.modelConfig();
        var boundedConfig = new com.enjoy.agent.modelgateway.application.PreparedModelConfig(
                config.provider(), config.modelType(), config.modelName(), config.credentialSource(),
                config.credentialId(), config.credentialCiphertext(), config.temperature(),
                mcpRuntimeProperties.getDefaultMaxOutputTokensPerModelCall(),
                config.officialModelConfigId(), config.baseUrl(), config.inputPricePerMillion(),
                config.outputPricePerMillion(), config.currency());
        return new PreparedChatTurn(
                turn.tenantId(), turn.userId(), turn.agentId(), turn.sessionId(), turn.userMessageId(),
                turn.latestUserMessageContent(), turn.systemPrompt(), turn.sessionMemory(), boundedConfig,
                turn.knowledgeBase(), turn.mcpTools(), turn.retrievalContext(), turn.retrievalDebug(),
                turn.historyMessages(), turn.workflowId(), turn.mcpToolApprovalTokens());
    }

    private Map<String, PreparedMcpTool> indexToolsByUniqueName(List<PreparedMcpTool> tools) {
        Map<String, PreparedMcpTool> toolsByName = new LinkedHashMap<>();
        for (PreparedMcpTool tool : tools) {
            PreparedMcpTool existing = toolsByName.putIfAbsent(tool.name(), tool);
            if (existing != null) {
                throw new ApiException(
                        "MCP_TOOL_NAME_CONFLICT",
                        "Multiple bound MCP servers expose the same tool name: " + tool.name(),
                        HttpStatus.BAD_REQUEST
                );
            }
        }
        return toolsByName;
    }

    private String truncateForModel(String value) {
        if (value == null || value.length() <= mcpRuntimeProperties.getMaxToolResultChars()) {
            return value;
        }
        return value.substring(0, mcpRuntimeProperties.getMaxToolResultChars());
    }
}
