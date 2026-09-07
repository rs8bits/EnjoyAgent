package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE delta：最终助手回答的一个增量片段。 */
public record SupportDeltaEvent(UUID turnId, String delta) {
}
