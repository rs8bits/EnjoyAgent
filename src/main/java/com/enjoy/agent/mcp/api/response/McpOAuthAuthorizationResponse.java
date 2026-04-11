package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 发起 MCP OAuth 授权响应。
 */
@Schema(name = "McpOAuthAuthorizationResponse", description = "发起 MCP OAuth 授权响应")
public record McpOAuthAuthorizationResponse(
        @Schema(description = "当前连接状态")
        String status,

        @Schema(description = "跳转到授权服务器的 URL")
        String authorizationUrl,

        @Schema(description = "Protected resource metadata URL")
        String resourceMetadataUrl,

        @Schema(description = "Authorization server issuer")
        String authorizationServerIssuer,

        @Schema(description = "state 的过期时间")
        Instant authorizationExpiresAt
) {
}
