package com.enjoy.agent.mcp.application;

/**
 * MCP Tool 调用结果。
 */
public record McpToolCallResult(
        String modelVisibleContent,
        String rawResponsePayload
) {
}
