package com.enjoy.agent.chat.application;

import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpRuntimeProperties;
import com.enjoy.agent.mcp.application.McpToolCallFailure;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.modelgateway.application.ModelCallLogService;
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

/**
 * 处理带 MCP 工具调用的聊天编排。
 */
@Service
public class McpChatOrchestratorService {

    private final ModelGatewayService modelGatewayService;
    private final ModelCallLogService modelCallLogService;
    private final McpGatewayService mcpGatewayService;
    private final McpToolCallLogService mcpToolCallLogService;
    private final McpRuntimeProperties mcpRuntimeProperties;
    private final ObjectMapper objectMapper;

    public McpChatOrchestratorService(
            ModelGatewayService modelGatewayService,
            ModelCallLogService modelCallLogService,
            McpGatewayService mcpGatewayService,
            McpToolCallLogService mcpToolCallLogService,
            McpRuntimeProperties mcpRuntimeProperties,
            ObjectMapper objectMapper
    ) {
        this.modelGatewayService = modelGatewayService;
        this.modelCallLogService = modelCallLogService;
        this.mcpGatewayService = mcpGatewayService;
        this.mcpToolCallLogService = mcpToolCallLogService;
        this.mcpRuntimeProperties = mcpRuntimeProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 在一个聊天轮次内执行模型与工具的往返调用，直到拿到最终文本回复。
     */
    public ModelGatewayResult completeTurn(PreparedChatTurn preparedChatTurn) {
        Map<String, PreparedMcpTool> toolsByName = preparedChatTurn.mcpTools().stream()
                .collect(LinkedHashMap::new, (map, tool) -> map.put(tool.name(), tool), LinkedHashMap::putAll);
        List<ModelGatewayToolDefinition> toolDefinitions = preparedChatTurn.mcpTools().stream()
                .map(tool -> new ModelGatewayToolDefinition(tool.name(), tool.description(), tool.inputSchemaJson()))
                .toList();
        List<ModelGatewayConversationMessage> conversationMessages = preparedChatTurn.historyMessages().stream()
                .map(message -> message.role() == com.enjoy.agent.chat.domain.enums.ChatMessageRole.USER
                        ? ModelGatewayConversationMessage.user(message.content())
                        : ModelGatewayConversationMessage.assistant(message.content()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        for (int round = 0; round < mcpRuntimeProperties.getMaxToolRounds(); round++) {
            ModelGatewayChatCompletion completion = invokeModel(preparedChatTurn, conversationMessages, toolDefinitions);
            if (completion.toolCalls().isEmpty()) {
                return completion.toResult();
            }

            conversationMessages.add(ModelGatewayConversationMessage.assistantToolCalls(completion.toolCalls()));
            for (ModelGatewayToolCall toolCall : completion.toolCalls()) {
                String toolMessage = executeTool(preparedChatTurn, toolsByName, toolCall);
                conversationMessages.add(ModelGatewayConversationMessage.tool(toolCall.id(), toolMessage));
            }
        }

        throw new ModelGatewayInvocationException(
                "MODEL_TOOL_LOOP_EXCEEDED",
                "Model exceeded maximum MCP tool calling rounds",
                HttpStatus.BAD_GATEWAY,
                preparedChatTurn.modelConfig().provider(),
                preparedChatTurn.modelConfig().modelName(),
                preparedChatTurn.modelConfig().credentialId() == null
                        ? com.enjoy.agent.modelgateway.domain.enums.CredentialSource.PLATFORM
                        : com.enjoy.agent.modelgateway.domain.enums.CredentialSource.USER,
                preparedChatTurn.modelConfig().credentialId(),
                0L,
                null
        );
    }

    private ModelGatewayChatCompletion invokeModel(
            PreparedChatTurn preparedChatTurn,
            List<ModelGatewayConversationMessage> conversationMessages,
            List<ModelGatewayToolDefinition> toolDefinitions
    ) {
        try {
            ModelGatewayChatCompletion completion = modelGatewayService.generateChatCompletion(
                    preparedChatTurn,
                    conversationMessages,
                    toolDefinitions
            );
            modelCallLogService.recordSuccess(preparedChatTurn, completion);
            return completion;
        } catch (ModelGatewayInvocationException ex) {
            throw ex;
        }
    }

    private String executeTool(
            PreparedChatTurn preparedChatTurn,
            Map<String, PreparedMcpTool> toolsByName,
            ModelGatewayToolCall toolCall
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

        try {
            Map<String, Object> arguments = parseArguments(toolCall.argumentsJson());
            McpToolCallResult result = mcpGatewayService.callTool(tool, arguments);
            long latencyMs = elapsedMillis(startNanos);
            mcpToolCallLogService.recordSuccess(preparedChatTurn, tool, toolCall.id(), requestPayload, result, latencyMs);
            return truncateForModel(result.modelVisibleContent());
        } catch (RuntimeException ex) {
            McpToolCallFailure failure = new McpToolCallFailure(
                    "MCP_TOOL_INVOCATION_FAILED",
                    ex.getMessage() == null ? "MCP tool invocation failed" : ex.getMessage(),
                    elapsedMillis(startNanos),
                    ex
            );
            mcpToolCallLogService.recordFailure(preparedChatTurn, tool, toolCall.id(), requestPayload, failure);
            return "Tool call failed: " + failure.getCode() + " - " + failure.getMessage();
        }
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

    private String truncateForModel(String value) {
        if (value == null || value.length() <= mcpRuntimeProperties.getMaxToolResultChars()) {
            return value;
        }
        return value.substring(0, mcpRuntimeProperties.getMaxToolResultChars());
    }
}
