package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE completed：最终助手消息已经完整入库。 */
public record SupportCompletedEvent(UUID turnId, SupportMessageResponse assistantMessage) {
}
