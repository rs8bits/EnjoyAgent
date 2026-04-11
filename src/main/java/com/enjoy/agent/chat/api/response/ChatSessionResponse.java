package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 聊天会话返回对象。
 */
@Schema(name = "ChatSessionResponse", description = "聊天会话返回对象")
public record ChatSessionResponse(
        @Schema(description = "会话 ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "Agent ID")
        Long agentId,

        @Schema(description = "Agent 名称")
        String agentName,

        @Schema(description = "会话标题")
        String title,

        @Schema(description = "创建人 ID")
        Long createdByUserId,

        @Schema(description = "创建人显示名")
        String createdByDisplayName,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "最后活跃时间")
        Instant updatedAt
) {
}
