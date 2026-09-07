package com.enjoy.agent.support.api.response;

import java.time.Instant;
import java.util.UUID;

/**
 * SSE 确认事件只返回可展示摘要和确认 ID，不把原始工具参数交给浏览器。
 */
public record SupportConfirmationRequiredEvent(
        UUID turnId,
        UUID confirmationId,
        String skillName,
        String title,
        String summary,
        Instant expiresAt
) {
}
