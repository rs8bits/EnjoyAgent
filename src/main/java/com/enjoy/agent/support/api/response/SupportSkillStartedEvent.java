package com.enjoy.agent.support.api.response;

import java.time.Instant;
import java.util.UUID;

/** SSE skill_started：仅暴露安全的运行状态和关联 ID。 */
public record SupportSkillStartedEvent(
        UUID turnId,
        UUID callId,
        String skillName,
        String displayName,
        Instant startedAt
) {
}
