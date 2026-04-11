package com.enjoy.agent.mcp.application;

import java.time.Instant;

/**
 * OAuth token 交换/刷新结果。
 */
public record McpOAuthTokenResult(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        String grantedScopes
) {
}
