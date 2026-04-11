package com.enjoy.agent.mcp.application;

import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;

/**
 * MCP Tool 运行时快照。
 */
public record PreparedMcpTool(
        Long id,
        String name,
        String description,
        String inputSchemaJson,
        McpToolRiskLevel riskLevel,
        PreparedMcpServer server
) {
}
