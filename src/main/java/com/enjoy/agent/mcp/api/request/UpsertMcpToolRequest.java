package com.enjoy.agent.mcp.api.request;

import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * MCP Tool 录入或同步请求。
 */
@Schema(name = "UpsertMcpToolRequest", description = "MCP Tool 录入或同步请求")
public record UpsertMcpToolRequest(
        @Schema(description = "工具名称，在同一个 Server 下必须唯一", example = "search_messages")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "工具描述", example = "搜索最近 30 天内的聊天消息")
        @Size(max = 1000)
        String description,

        @Schema(description = "工具输入参数 schema 的 JSON 字符串")
        String inputSchemaJson,

        @Schema(description = "工具风险等级", example = "LOW")
        McpToolRiskLevel riskLevel,

        @Schema(description = "是否启用", example = "true")
        Boolean enabled
) {
}
