package com.enjoy.agent.mcp.application;

/**
 * 从远端 MCP Server 发现出来的工具定义。
 */
public record DiscoveredMcpTool(
        String name,
        String description,
        String inputSchemaJson
) {
}
