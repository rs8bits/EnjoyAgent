package com.enjoy.agent.workflow.application;

import java.util.Map;

public record NodeExecutionResult(
        String nextHandle,
        Map<String, Object> output
) {
}
