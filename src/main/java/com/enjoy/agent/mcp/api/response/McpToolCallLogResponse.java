package com.enjoy.agent.mcp.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * MCP 工具调用日志返回对象。
 */
@Schema(name = "McpToolCallLogResponse", description = "MCP 工具调用日志返回对象")
public record McpToolCallLogResponse(
        @Schema(description = "日志 ID")
        Long id,

        @Schema(description = "Agent ID")
        Long agentId,

        @Schema(description = "Session ID")
        Long sessionId,

        @Schema(description = "Server ID")
        Long serverId,

        @Schema(description = "Tool ID")
        Long toolId,

        @Schema(description = "Tool call ID")
        String toolCallId,

        @Schema(description = "工具名称")
        String toolName,

        @Schema(description = "状态")
        String status,

        @Schema(description = "耗时毫秒")
        Long latencyMs,

        @Schema(description = "错误码")
        String errorCode,

        @Schema(description = "错误消息")
        String errorMessage,

        @Schema(description = "请求载荷")
        String requestPayload,

        @Schema(description = "响应载荷")
        String responsePayload,

        @Schema(description = "创建时间")
        Instant createdAt
) {
}
