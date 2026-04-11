package com.enjoy.agent.mcp.application;

import com.enjoy.agent.mcp.domain.enums.McpTransportType;

/**
 * MCP Server 运行时快照。
 */
public record PreparedMcpServer(
        Long id,
        String name,
        String baseUrl,
        McpTransportType transportType,
        String bearerToken
) {
}
