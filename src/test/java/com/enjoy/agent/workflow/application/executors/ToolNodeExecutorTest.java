package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpHighRiskApprovalService;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpServer;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowExecutionBudget;
import com.enjoy.agent.workflow.application.WorkflowRuntimeProperties;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class ToolNodeExecutorTest {

    @Test
    void confirmedHighRiskResultStillUsesToolAuditAndNeverRepeatsRemoteCall() {
        var runtime = Mockito.mock(McpRuntimeService.class);
        var gateway = Mockito.mock(McpGatewayService.class);
        var logs = Mockito.mock(McpToolCallLogService.class);
        var actions = Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class);
        var selected = tool(McpToolRiskLevel.HIGH);
        when(runtime.getRunnableToolForTenant(7L, 13L)).thenReturn(selected);
        var node = new WorkflowNode();
        node.setNodeType(WorkflowNodeType.TOOL);
        node.setNodeKey("delete_step");
        node.setConfigJson("{\"toolId\":13,\"arguments\":{\"recordId\":42}}");
        var turn = Mockito.mock(PreparedChatTurn.class);
        when(turn.userMessageId()).thenReturn(55L);
        var result = new McpToolCallResult("confirmed result", "{}");
        when(actions.requireResult(7L,11L,5L,8L,"chat:55:delete_step",selected,Map.of("recordId",42))).thenReturn(result);
        var executor = new ToolNodeExecutor(runtime,gateway,Mockito.mock(McpHighRiskApprovalService.class),logs,new ObjectMapper(),actions);
        assertThat(executor.execute(context(node,turn,new WorkflowExecutionBudget(new WorkflowRuntimeProperties()))).output()).containsEntry("tool_result","confirmed result");
        verify(logs).recordSuccess(eq(turn),eq(selected),eq("workflow:delete_step"),any(),eq(result),org.mockito.ArgumentMatchers.anyLong());
        Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void execute_shouldRequireAgentContextBeforeLoadingTools() {
        ToolNodeExecutor executor = new ToolNodeExecutor(
                Mockito.mock(com.enjoy.agent.mcp.application.McpRuntimeService.class),
                Mockito.mock(com.enjoy.agent.mcp.application.McpGatewayService.class),
                Mockito.mock(com.enjoy.agent.mcp.application.McpHighRiskApprovalService.class),
                Mockito.mock(com.enjoy.agent.mcp.application.McpToolCallLogService.class),
                new ObjectMapper(),
                org.mockito.Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class));

        WorkflowNode node = new WorkflowNode();
        node.setNodeType(WorkflowNodeType.TOOL);
        node.setName("工具调用");
        node.setConfigJson("{\"toolName\":\"search\"}");

        NodeExecutionContext context = new NodeExecutionContext(
                7L,
                11L,
                null,
                null,
                node,
                null,
                new HashMap<>()
        );

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    org.assertj.core.api.Assertions.assertThat(apiException.getCode())
                            .isEqualTo("WORKFLOW_TEST_AGENT_REQUIRED");
                    org.assertj.core.api.Assertions.assertThat(apiException.getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void exhaustedBudgetDoesNotConsumeOneTimeHighRiskApproval() {
        McpRuntimeService runtimeService = Mockito.mock(McpRuntimeService.class);
        McpGatewayService gatewayService = Mockito.mock(McpGatewayService.class);
        McpHighRiskApprovalService approvalService = Mockito.mock(McpHighRiskApprovalService.class);
        McpToolCallLogService logService = Mockito.mock(McpToolCallLogService.class);
        PreparedMcpTool tool = tool(McpToolRiskLevel.HIGH);
        when(runtimeService.getRunnableToolForTenant(7L, 13L)).thenReturn(tool);
        ToolNodeExecutor executor = new ToolNodeExecutor(
                runtimeService, gatewayService, approvalService, logService, new ObjectMapper(), org.mockito.Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class));
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxToolCalls(1);
        WorkflowExecutionBudget budget = new WorkflowExecutionBudget(properties);
        budget.beforeToolCall();

        assertThatThrownBy(() -> executor.execute(context(toolNode(), null, budget)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("WORKFLOW_TOOL_CALL_BUDGET_EXCEEDED"));
        verify(approvalService, never()).requireApproved(any(), any(), any(), any(), any());
        verify(gatewayService, never()).callTool(any(), any());
    }

    @Test
    void successfulWorkflowToolCallIsAuditedAndNullPayloadsAreSafe() {
        McpRuntimeService runtimeService = Mockito.mock(McpRuntimeService.class);
        McpGatewayService gatewayService = Mockito.mock(McpGatewayService.class);
        McpHighRiskApprovalService approvalService = Mockito.mock(McpHighRiskApprovalService.class);
        McpToolCallLogService logService = Mockito.mock(McpToolCallLogService.class);
        PreparedMcpTool tool = tool(McpToolRiskLevel.LOW);
        PreparedChatTurn accountingTurn = Mockito.mock(PreparedChatTurn.class);
        when(runtimeService.getRunnableToolForTenant(7L, 13L)).thenReturn(tool);
        when(gatewayService.callTool(eq(tool), any())).thenReturn(new McpToolCallResult(null, null, false));
        ToolNodeExecutor executor = new ToolNodeExecutor(
                runtimeService, gatewayService, approvalService, logService, new ObjectMapper(), org.mockito.Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class));

        NodeExecutionResult result = executor.execute(context(toolNode(), accountingTurn, null));

        assertThat(result.output()).containsEntry("tool_result", "").containsEntry("tool_raw_response", "");
        verify(logService).recordSuccess(
                eq(accountingTurn), eq(tool), eq("workflow:tool"), eq("{\"input\":\"\"}"), any(), any());
        verify(gatewayService).callTool(eq(tool), any());
    }

    @Test
    void serverReportedToolErrorIsAuditedAndFailsTheWorkflow() {
        McpRuntimeService runtimeService = Mockito.mock(McpRuntimeService.class);
        McpGatewayService gatewayService = Mockito.mock(McpGatewayService.class);
        McpHighRiskApprovalService approvalService = Mockito.mock(McpHighRiskApprovalService.class);
        McpToolCallLogService logService = Mockito.mock(McpToolCallLogService.class);
        PreparedMcpTool tool = tool(McpToolRiskLevel.LOW);
        PreparedChatTurn accountingTurn = Mockito.mock(PreparedChatTurn.class);
        when(runtimeService.getRunnableToolForTenant(7L, 13L)).thenReturn(tool);
        when(gatewayService.callTool(eq(tool), any()))
                .thenReturn(new McpToolCallResult("remote error", "{\"secret\":\"x\"}", true));
        ToolNodeExecutor executor = new ToolNodeExecutor(
                runtimeService, gatewayService, approvalService, logService, new ObjectMapper(), org.mockito.Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class));

        assertThatThrownBy(() -> executor.execute(context(toolNode(), accountingTurn, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("MCP_TOOL_REPORTED_ERROR"));
        verify(logService).recordFailure(
                eq(accountingTurn), eq(tool), eq("workflow:tool"), any(),
                eq("{\"secret\":\"x\"}"), any());
        verify(gatewayService).callTool(eq(tool), any());
    }

    @Test
    void recursivelyResolvesNodeSelectorsAndPreservesExactValueTypes() {
        McpRuntimeService runtimeService = Mockito.mock(McpRuntimeService.class);
        McpGatewayService gatewayService = Mockito.mock(McpGatewayService.class);
        PreparedMcpTool tool = tool(McpToolRiskLevel.LOW);
        when(runtimeService.getRunnableToolForTenant(7L, 13L)).thenReturn(tool);
        when(gatewayService.callTool(eq(tool), any()))
                .thenReturn(new McpToolCallResult("ok", "{}", false));
        ToolNodeExecutor executor = new ToolNodeExecutor(
                runtimeService,
                gatewayService,
                Mockito.mock(McpHighRiskApprovalService.class),
                Mockito.mock(McpToolCallLogService.class),
                new ObjectMapper(), org.mockito.Mockito.mock(com.enjoy.agent.mcp.application.McpActionConfirmationService.class));
        WorkflowNode node = toolNode();
        node.setConfigJson("{\"toolId\":13,\"arguments\":{"
                + "\"payload\":\"{{#producer.payload#}}\","
                + "\"label\":\"score={{node_outputs.producer.payload.score}}\","
                + "\"nested\":{\"active\":\"{{producer.payload.active}}\"}}}");
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put("node_outputs", Map.of("producer", Map.of(
                "payload", Map.of("score", 7, "active", true))));
        NodeExecutionContext context = new NodeExecutionContext(
                7L, 11L, 5L, 8L, node, null, contextMap);

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gatewayService).callTool(eq(tool), argumentsCaptor.capture());
        assertThat(argumentsCaptor.getValue().get("payload"))
                .isEqualTo(Map.of("score", 7, "active", true));
        assertThat(argumentsCaptor.getValue()).containsEntry("label", "score=7");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) argumentsCaptor.getValue().get("nested");
        assertThat(nested).containsEntry("active", true);
    }

    private WorkflowNode toolNode() {
        WorkflowNode node = new WorkflowNode();
        node.setId(3L);
        node.setNodeKey("tool");
        node.setNodeType(WorkflowNodeType.TOOL);
        node.setName("Tool");
        node.setConfigJson("{\"toolId\":13}");
        return node;
    }

    private PreparedMcpTool tool(McpToolRiskLevel riskLevel) {
        return new PreparedMcpTool(
                13L, "demo", "demo", "{}", riskLevel,
                new PreparedMcpServer(
                        17L, "server", "https://example.com/mcp",
                        McpTransportType.STREAMABLE_HTTP, null));
    }

    private NodeExecutionContext context(
            WorkflowNode node,
            PreparedChatTurn accountingTurn,
            WorkflowExecutionBudget budget
    ) {
        return new NodeExecutionContext(
                7L, 11L, 5L, 8L, node, null, new HashMap<>(), accountingTurn,
                null, budget, Map.of());
    }
}
