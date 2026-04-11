package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 聊天消息返回对象。
 */
@Schema(name = "ChatMessageResponse", description = "聊天消息返回对象")
public record ChatMessageResponse(
        @Schema(description = "消息 ID")
        Long id,

        @Schema(description = "所属会话 ID")
        Long sessionId,

        @Schema(description = "消息角色", example = "USER")
        String role,

        @Schema(description = "消息内容")
        String content,

        @Schema(description = "创建时间")
        Instant createdAt
) {
}
