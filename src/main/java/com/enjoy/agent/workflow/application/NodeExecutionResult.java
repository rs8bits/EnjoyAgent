package com.enjoy.agent.workflow.application;

import java.util.Map;

public record NodeExecutionResult(
        String nextHandle,
        Map<String, Object> output,
        Map<String, Map<String, Object>> childNodeOutputs
) {
    public NodeExecutionResult(String nextHandle, Map<String, Object> output) {
        this(nextHandle, output, Map.of());
    }

    public NodeExecutionResult {
        childNodeOutputs = childNodeOutputs == null ? Map.of() : childNodeOutputs;
    }
}
