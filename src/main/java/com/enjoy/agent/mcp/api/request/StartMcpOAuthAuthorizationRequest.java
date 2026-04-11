package com.enjoy.agent.mcp.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起 MCP OAuth 授权请求。
 */
@Schema(name = "StartMcpOAuthAuthorizationRequest", description = "发起 MCP OAuth 授权请求")
public record StartMcpOAuthAuthorizationRequest(
        @Schema(description = "已在授权服务器预注册的 OAuth client_id")
        @NotBlank
        @Size(max = 255)
        String clientId,

        @Schema(description = "OAuth client_secret；对于 public client 可为空")
        @Size(max = 2000)
        String clientSecret,

        @Schema(description = "请求的 scope，使用空格分隔", example = "openid profile mcp")
        @Size(max = 1000)
        String scope
) {
}
