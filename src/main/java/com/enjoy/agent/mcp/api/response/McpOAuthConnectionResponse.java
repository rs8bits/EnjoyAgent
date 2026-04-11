package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * MCP OAuth 连接状态响应。
 */
@Schema(name = "McpOAuthConnectionResponse", description = "MCP OAuth 连接状态响应")
public record McpOAuthConnectionResponse(
        @Schema(description = "Server ID")
        Long serverId,

        @Schema(description = "连接状态")
        String status,

        @Schema(description = "是否已有可用 access token")
        boolean connected,

        @Schema(description = "resource metadata URL")
        String resourceMetadataUrl,

        @Schema(description = "authorization server issuer")
        String authorizationServerIssuer,

        @Schema(description = "authorization endpoint")
        String authorizationEndpoint,

        @Schema(description = "token endpoint")
        String tokenEndpoint,

        @Schema(description = "client_id")
        String clientId,

        @Schema(description = "请求的 scopes")
        String requestedScopes,

        @Schema(description = "实际授权的 scopes")
        String grantedScopes,

        @Schema(description = "token 类型")
        String tokenType,

        @Schema(description = "access token 过期时间")
        Instant expiresAt,

        @Schema(description = "最近授权完成时间")
        Instant lastAuthorizedAt,

        @Schema(description = "最近 refresh 时间")
        Instant lastTokenRefreshedAt,

        @Schema(description = "最近错误码")
        String lastErrorCode,

        @Schema(description = "最近错误消息")
        String lastErrorMessage
) {
}
