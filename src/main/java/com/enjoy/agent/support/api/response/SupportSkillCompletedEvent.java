package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE skill_completed：summary 已经过输出 Hook，不包含原始工具响应。 */
public record SupportSkillCompletedEvent(
        UUID turnId,
        UUID callId,
        String skillName,
        String displayName,
        String status,
        String summary,
        Long durationMs
) {
}
