package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * MCP Tool 返回对象。
 */
@Schema(name = "McpToolResponse", description = "MCP Tool 返回对象")
public record McpToolResponse(
        @Schema(description = "Tool ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "所属 Server ID")
        Long serverId,

        @Schema(description = "所属 Server 名称")
        String serverName,

        @Schema(description = "Tool 名称")
        String name,

        @Schema(description = "Tool 描述")
        String description,

        @Schema(description = "Tool 输入参数 schema 的 JSON 字符串")
        String inputSchemaJson,

        @Schema(description = "风险等级")
        String riskLevel,

        @Schema(description = "是否启用")
        boolean enabled,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
