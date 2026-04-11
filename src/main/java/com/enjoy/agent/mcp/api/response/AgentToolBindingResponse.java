package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Agent 工具绑定返回对象。
 */
@Schema(name = "AgentToolBindingResponse", description = "Agent 当前绑定的 MCP Tool 信息")
public record AgentToolBindingResponse(
        @Schema(description = "绑定 ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "Agent ID")
        Long agentId,

        @Schema(description = "Tool ID")
        Long toolId,

        @Schema(description = "Tool 名称")
        String toolName,

        @Schema(description = "Server ID")
        Long serverId,

        @Schema(description = "Server 名称")
        String serverName,

        @Schema(description = "Tool 风险等级")
        String riskLevel,

        @Schema(description = "绑定是否启用")
        boolean enabled,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
