package com.enjoy.agent.support.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 前端可以展示的客服消息。 */
@Schema(name = "SupportMessageResponse", description = "客服会话消息")
public record SupportMessageResponse(
        Long id,
        Long conversationId,
        UUID turnId,
        long sequence,
        String role,
        String content,
        Instant createdAt
) {
}
