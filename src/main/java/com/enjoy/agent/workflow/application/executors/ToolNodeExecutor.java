package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpHighRiskApprovalService;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.McpToolCallFailure;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowVariableResolver;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ToolNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolNodeExecutor.class);
    private static final int MAX_ARGUMENT_DEPTH = 32;
    private static final int MAX_RESOLVED_ARGUMENT_STRING_LENGTH = 20_000;
    private static final int MAX_RESOLVED_ARGUMENT_JSON_LENGTH = 100_000;

    private final McpRuntimeService mcpRuntimeService;
    private final McpGatewayService mcpGatewayService;
    private final ObjectMapper objectMapper;
    private final McpHighRiskApprovalService approvalService;
    private final McpToolCallLogService toolCallLogService;

    public ToolNodeExecutor(
            McpRuntimeService mcpRuntimeService,
            McpGatewayService mcpGatewayService,
            McpHighRiskApprovalService approvalService,
            McpToolCallLogService toolCallLogService,
            ObjectMapper objectMapper
    ) {
        this.mcpRuntimeService = mcpRuntimeService;
        this.mcpGatewayService = mcpGatewayService;
        this.approvalService = approvalService;
        this.toolCallLogService = toolCallLogService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "TOOL";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        ToolNodeConfig config = parseConfig(ctx.node().getConfigJson());
        Map<String, Object> arguments = resolveArguments(ctx, config);
        String requestPayload = toJson(arguments);
        PreparedMcpTool tool = resolveTool(ctx, config);
        // Budget rejection must happen before consuming a one-time HIGH-risk approval.
        if (ctx.executionBudget() != null) {
            ctx.executionBudget().beforeToolCall();
        }
        String toolCallId = "workflow:" + ctx.node().getNodeKey();

        log.info("Calling MCP tool: name={}, toolId={}, agentId={}", tool.name(), tool.id(), ctx.agentId());
        long startNanos = System.nanoTime();
        McpToolCallResult callResult;
        try {
            if (tool.riskLevel() == McpToolRiskLevel.HIGH) {
                approvalService.requireApproved(
                        approvalTokens(ctx), ctx.tenantId(), ctx.userId(), ctx.agentId(), tool.id());
            }
            callResult = ctx.mcpTurnSession() == null
                    ? mcpGatewayService.callTool(tool, arguments)
                    : ctx.mcpTurnSession().callTool(tool, arguments);
        } catch (RuntimeException ex) {
            recordFailure(ctx, tool, toolCallId, requestPayload, null, ex, startNanos);
            if (ex instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(
                    "WORKFLOW_TOOL_INVOCATION_FAILED",
                    "Workflow tool invocation failed",
                    HttpStatus.BAD_GATEWAY);
        }
        if (callResult == null) {
            ApiException failure = new ApiException(
                    "WORKFLOW_TOOL_INVALID_RESPONSE", "Workflow tool returned no result", HttpStatus.BAD_GATEWAY);
            recordFailure(ctx, tool, toolCallId, requestPayload, null, failure, startNanos);
            throw failure;
        }
        if (callResult.error()) {
            ApiException failure = new ApiException(
                    "MCP_TOOL_REPORTED_ERROR",
                    "MCP server reported a tool execution error",
                    HttpStatus.BAD_GATEWAY);
            recordFailure(
                    ctx, tool, toolCallId, requestPayload, callResult.rawResponsePayload(), failure, startNanos);
            throw failure;
        }
        if (ctx.accountingTurn() != null) {
            toolCallLogService.recordSuccess(
                    ctx.accountingTurn(), tool, toolCallId, requestPayload, callResult, elapsedMillis(startNanos));
        }
        if (ctx.executionBudget() != null) {
            ctx.executionBudget().recordToolResult(
                    callResult.modelVisibleContent(), callResult.rawResponsePayload());
        }

        return new NodeExecutionResult(null, Map.of(
                "tool_result", nullToEmpty(callResult.modelVisibleContent()),
                "tool_raw_response", nullToEmpty(callResult.rawResponsePayload())
        ));
    }

    private PreparedMcpTool resolveTool(NodeExecutionContext ctx, ToolNodeConfig config) {
        if (config.toolId != null) {
            return mcpRuntimeService.getRunnableToolForTenant(ctx.tenantId(), config.toolId);
        }
        if (ctx.agentId() == null) {
            throw new ApiException(
                    "WORKFLOW_TEST_AGENT_REQUIRED",
                    "Tool node test requires either selecting a concrete tool or binding this workflow to an Agent first",
                    HttpStatus.BAD_REQUEST
            );
        }
        List<PreparedMcpTool> tools = mcpRuntimeService.listRunnableToolsForAgent(ctx.agentId());
        return tools.stream()
                .filter(t -> t.name().equals(config.toolName))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        "MCP_TOOL_NOT_BOUND",
                        "Tool not found or not runnable: " + config.toolName,
                        HttpStatus.BAD_REQUEST
                ));
    }

    private Map<String, Object> resolveArguments(NodeExecutionContext ctx, ToolNodeConfig config) {
        if (config.arguments != null && !config.arguments.isEmpty()) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : config.arguments.entrySet()) {
                resolved.put(entry.getKey(), resolveArgumentValue(entry.getValue(), ctx.context(), 0));
            }
            return resolved;
        }
        Object input = ctx.context().get("node_input");
        if (input == null) {
            input = ctx.context().getOrDefault("llm_output", ctx.context().getOrDefault("user_input", ""));
        }
        return Map.of("input", input == null ? "" : input);
    }

    private Object resolveArgumentValue(Object value, Map<String, Object> context, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw new ApiException(
                    "WORKFLOW_TOOL_ARGUMENTS_INVALID",
                    "Workflow tool arguments are nested too deeply",
                    HttpStatus.BAD_REQUEST);
        }
        if (value instanceof String template) {
            return WorkflowVariableResolver.resolveTypedTemplate(
                    template, context, objectMapper, MAX_RESOLVED_ARGUMENT_STRING_LENGTH,
                    () -> new ApiException(
                            "WORKFLOW_TOOL_ARGUMENTS_TOO_LONG",
                            "A resolved workflow tool argument must be at most "
                                    + MAX_RESOLVED_ARGUMENT_STRING_LENGTH + " characters",
                            HttpStatus.BAD_REQUEST));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()),
                        resolveArgumentValue(entry.getValue(), context, depth + 1));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveArgumentValue(item, context, depth + 1));
            }
            return resolved;
        }
        return value;
    }

    private List<String> approvalTokens(NodeExecutionContext ctx) {
        Object value = ctx.context().get("mcp_tool_approval_tokens");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private ToolNodeConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ToolNodeConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid tool node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private void recordFailure(
            NodeExecutionContext ctx,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            String responsePayload,
            RuntimeException cause,
            long startNanos
    ) {
        if (ctx.accountingTurn() == null) {
            return;
        }
        String code = cause instanceof ApiException apiException
                ? apiException.getCode() : "WORKFLOW_TOOL_INVOCATION_FAILED";
        McpToolCallFailure failure = new McpToolCallFailure(
                code, "Workflow tool invocation failed", elapsedMillis(startNanos), cause);
        toolCallLogService.recordFailure(
                ctx.accountingTurn(), tool, toolCallId, requestPayload, responsePayload, failure);
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            String json = objectMapper.writeValueAsString(arguments);
            if (json.length() > MAX_RESOLVED_ARGUMENT_JSON_LENGTH) {
                throw new ApiException(
                        "WORKFLOW_TOOL_ARGUMENTS_TOO_LONG",
                        "Resolved workflow tool arguments must be at most "
                                + MAX_RESOLVED_ARGUMENT_JSON_LENGTH + " characters",
                        HttpStatus.BAD_REQUEST);
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "WORKFLOW_TOOL_ARGUMENTS_INVALID",
                    "Workflow tool arguments could not be serialized",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ToolNodeConfig(
            Long toolId,
            String toolName,
            Map<String, Object> arguments
    ) {
    }
}
