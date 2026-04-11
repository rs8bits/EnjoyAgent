package com.enjoy.agent.mcp.domain.enums;

/**
 * MCP Server 认证模式。
 */
public enum McpAuthType {
    NONE,
    STATIC_BEARER,
    OAUTH_AUTH_CODE,
    OAUTH_CLIENT_CREDENTIALS
}
