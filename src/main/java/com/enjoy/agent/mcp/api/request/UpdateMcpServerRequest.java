package com.enjoy.agent.mcp.api.request;

import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新 MCP Server 请求。
 */
@Schema(name = "UpdateMcpServerRequest", description = "更新 MCP Server 请求")
public record UpdateMcpServerRequest(
        @Schema(description = "Server 名称，同一租户内必须唯一", example = "Slack MCP")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "Server 描述", example = "团队内部 Slack 工具服务")
        @Size(max = 512)
        String description,

        @Schema(description = "MCP Server 的基础地址", example = "https://mcp.example.com")
        @NotBlank
        @Size(max = 512)
        String baseUrl,

        @Schema(description = "MCP 传输类型", example = "STREAMABLE_HTTP")
        @NotNull
        McpTransportType transportType,

        @Schema(description = "认证模式；不传时按 credentialId 自动推断", example = "OAUTH_AUTH_CODE")
        McpAuthType authType,

        @Schema(description = "用于访问该 MCP Server 的凭证 ID，可为空", example = "3")
        Long credentialId,

        @Schema(description = "是否启用", example = "true")
        @NotNull
        Boolean enabled
) {
}
