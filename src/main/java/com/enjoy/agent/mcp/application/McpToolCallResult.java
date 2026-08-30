package com.enjoy.agent.mcp.application;

/**
 * MCP Tool 调用结果。
 * rawResponsePayload 是 SDK 解析后的标准 CallToolResult 再序列化结果，不代表原始 HTTP 字节。
 */
public record McpToolCallResult(
        String modelVisibleContent,
        String rawResponsePayload,
        boolean error
) {

    public McpToolCallResult(String modelVisibleContent, String rawResponsePayload) {
        this(modelVisibleContent, rawResponsePayload, false);
    }
}
