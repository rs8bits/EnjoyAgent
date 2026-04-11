package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * MCP Server 返回对象。
 */
@Schema(name = "McpServerResponse", description = "MCP Server 返回对象")
public record McpServerResponse(
        @Schema(description = "Server ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "Server 名称")
        String name,

        @Schema(description = "Server 描述")
        String description,

        @Schema(description = "基础地址")
        String baseUrl,

        @Schema(description = "传输类型")
        String transportType,

        @Schema(description = "认证模式")
        String authType,

        @Schema(description = "凭证 ID")
        Long credentialId,

        @Schema(description = "凭证名称")
        String credentialName,

        @Schema(description = "是否启用")
        boolean enabled,

        @Schema(description = "当前工具数量")
        long toolCount,

        @Schema(description = "上次同步工具目录时间")
        Instant lastSyncedAt,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
