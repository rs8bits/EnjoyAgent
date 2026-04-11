package com.enjoy.agent.mcp.application;

/**
 * MCP OAuth 发现结果。
 */
public record McpOAuthDiscoveryResult(
        String resourceMetadataUrl,
        String authorizationServerIssuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String resourceIndicator,
        boolean pkceS256Supported
) {
}
