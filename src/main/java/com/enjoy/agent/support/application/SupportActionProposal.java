package com.enjoy.agent.support.application;

import java.time.Instant;
import java.util.UUID;

/** 编排器创建确认提案后需要发送给当前浏览器的最小安全信息。 */
public record SupportActionProposal(
        UUID id,
        String skillName,
        String title,
        String summary,
        Instant expiresAt,
        String modelPayload
) {
}
