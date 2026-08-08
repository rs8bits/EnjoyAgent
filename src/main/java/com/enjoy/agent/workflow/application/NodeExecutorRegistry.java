package com.enjoy.agent.workflow.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NodeExecutorRegistry {

    private final Map<String, WorkflowNodeExecutor> executorsByType;

    public NodeExecutorRegistry(List<WorkflowNodeExecutor> executors) {
        this.executorsByType = executors.stream()
                .collect(Collectors.toUnmodifiableMap(WorkflowNodeExecutor::nodeType, e -> e));
    }

    public WorkflowNodeExecutor get(String nodeType) {
        WorkflowNodeExecutor executor = executorsByType.get(nodeType);
        if (executor == null) {
            throw new IllegalArgumentException("No executor registered for node type: " + nodeType);
        }
        return executor;
    }
}
