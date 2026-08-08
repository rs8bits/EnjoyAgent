package com.enjoy.agent.workflow.application;

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
        Map<String, Object> context
) {
    public NodeExecutionContext {
        if (context == null) {
            context = new HashMap<>();
        }
    }

    public NodeExecutionContext withNode(WorkflowNode newNode) {
        return new NodeExecutionContext(tenantId, userId, agentId, sessionId, newNode, modelConfig, context);
    }
}
