package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE accepted：用户消息已安全校验并持久化。 */
public record SupportAcceptedEvent(
        Long conversationId,
        UUID turnId,
        SupportMessageResponse userMessage
) {
}
