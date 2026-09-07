package com.enjoy.agent.support.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 当前用户在当前租户中的唯一客服会话。 */
@Schema(name = "SupportConversationResponse", description = "当前工作区唯一 AI 客服会话")
public record SupportConversationResponse(
        Long id,
        Long tenantId,
        Long userId,
        Instant createdAt,
        Instant updatedAt,
        long lastMessageSequence
) {
}
