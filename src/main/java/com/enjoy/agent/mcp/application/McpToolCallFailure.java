package com.enjoy.agent.mcp.application;

/**
 * MCP Tool 调用失败时用于日志的异常载体。
 */
public class McpToolCallFailure extends RuntimeException {

    private final String code;
    private final Long latencyMs;

    public McpToolCallFailure(String code, String message, Long latencyMs, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.latencyMs = latencyMs;
    }

    public String getCode() {
        return code;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }
}
