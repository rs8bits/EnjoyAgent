package com.enjoy.agent.mcp.application;

/** Raised by the HTTP transport before an oversized MCP response is materialized. */
final class McpResponseLimitException extends RuntimeException {

    McpResponseLimitException(long maxBytes) {
        super("MCP HTTP response exceeded the configured " + maxBytes + " byte limit");
    }
}
