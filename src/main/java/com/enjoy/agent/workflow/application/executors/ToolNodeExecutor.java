package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ToolNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolNodeExecutor.class);

    private final McpRuntimeService mcpRuntimeService;
    private final McpGatewayService mcpGatewayService;
    private final ObjectMapper objectMapper;

    public ToolNodeExecutor(
            McpRuntimeService mcpRuntimeService,
            McpGatewayService mcpGatewayService,
            ObjectMapper objectMapper
    ) {
        this.mcpRuntimeService = mcpRuntimeService;
        this.mcpGatewayService = mcpGatewayService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "TOOL";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        ToolNodeConfig config = parseConfig(ctx.node().getConfigJson());
        PreparedMcpTool tool = resolveTool(ctx, config);

        Map<String, Object> arguments = resolveArguments(ctx, config);

        log.info("Calling MCP tool: name={}, toolId={}, agentId={}", tool.name(), tool.id(), ctx.agentId());
        McpToolCallResult callResult = mcpGatewayService.callTool(tool, arguments);

        return new NodeExecutionResult(null, Map.of(
                "tool_result", callResult.modelVisibleContent(),
                "tool_raw_response", callResult.rawResponsePayload()
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
            Map<String, Object> resolved = new HashMap<>();
            for (Map.Entry<String, Object> entry : config.arguments.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s && s.startsWith("{{") && s.endsWith("}}")) {
                    String contextKey = s.substring(2, s.length() - 2).trim();
                    resolved.put(entry.getKey(), ctx.context().getOrDefault(contextKey, ""));
                } else {
                    resolved.put(entry.getKey(), value);
                }
            }
            return resolved;
        }
        String toolResult = (String) ctx.context().getOrDefault("llm_output",
                ctx.context().getOrDefault("user_input", ""));
        return Map.of("input", toolResult);
    }

    private ToolNodeConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ToolNodeConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid tool node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private record ToolNodeConfig(
            Long toolId,
            String toolName,
            Map<String, Object> arguments
    ) {
    }
}
