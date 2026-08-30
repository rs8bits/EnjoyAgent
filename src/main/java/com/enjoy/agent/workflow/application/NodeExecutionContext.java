package com.enjoy.agent.workflow.application;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import java.util.HashMap;
import java.util.Map;

public record NodeExecutionContext(
        Long tenantId,
        Long userId,
        Long agentId,
        Long sessionId,
        WorkflowNode node,
        PreparedModelConfig modelConfig,
        Map<String, Object> context,
        PreparedChatTurn accountingTurn,
        McpGatewayService.McpTurnSession mcpTurnSession,
        WorkflowExecutionBudget executionBudget,
        Map<String, WorkflowNode> workflowNodesByKey
) {
    public NodeExecutionContext {
        if (context == null) {
            context = new HashMap<>();
        }
        workflowNodesByKey = workflowNodesByKey == null ? Map.of() : Map.copyOf(workflowNodesByKey);
    }

    public NodeExecutionContext(
            Long tenantId,
            Long userId,
            Long agentId,
            Long sessionId,
            WorkflowNode node,
            PreparedModelConfig modelConfig,
            Map<String, Object> context
    ) {
        this(tenantId, userId, agentId, sessionId, node, modelConfig, context,
                null, null, null, Map.of());
    }

    public NodeExecutionContext(
            Long tenantId, Long userId, Long agentId, Long sessionId, WorkflowNode node,
            PreparedModelConfig modelConfig, Map<String, Object> context, PreparedChatTurn accountingTurn
    ) {
        this(tenantId, userId, agentId, sessionId, node, modelConfig, context,
                accountingTurn, null, null, Map.of());
    }

    public NodeExecutionContext(
            Long tenantId, Long userId, Long agentId, Long sessionId, WorkflowNode node,
            PreparedModelConfig modelConfig, Map<String, Object> context, PreparedChatTurn accountingTurn,
            McpGatewayService.McpTurnSession mcpTurnSession
    ) {
        this(tenantId, userId, agentId, sessionId, node, modelConfig, context,
                accountingTurn, mcpTurnSession, null, Map.of());
    }

    public NodeExecutionContext withNode(WorkflowNode newNode) {
        return withNodeAndContext(newNode, context);
    }

    public NodeExecutionContext withNodeAndContext(WorkflowNode newNode, Map<String, Object> newContext) {
        return new NodeExecutionContext(
                tenantId, userId, agentId, sessionId, newNode, modelConfig, newContext,
                accountingTurn, mcpTurnSession, executionBudget, workflowNodesByKey);
    }
}
