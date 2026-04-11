package com.enjoy.agent.mcp.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 使用 client credentials 建立 MCP OAuth 连接。
 */
@Schema(name = "ConnectMcpOAuthClientCredentialsRequest", description = "使用 OAuth client credentials 建立 MCP 连接")
public record ConnectMcpOAuthClientCredentialsRequest(
        @Schema(description = "已在授权服务器预注册的 OAuth client_id")
        @NotBlank
        @Size(max = 255)
        String clientId,

        @Schema(description = "OAuth client_secret")
        @NotBlank
        @Size(max = 2000)
        String clientSecret,

        @Schema(description = "请求的 scope，使用空格分隔", example = "openid profile mcp")
        @Size(max = 1000)
        String scope
) {
}
