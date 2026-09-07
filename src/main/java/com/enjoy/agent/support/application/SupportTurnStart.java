package com.enjoy.agent.support.application;

import com.enjoy.agent.support.api.response.SupportMessageResponse;
import java.util.UUID;

/** beginTurn 的事务结果。replay=true 时不会再次执行 Skill 或模型。 */
public record SupportTurnStart(
        Long conversationId,
        UUID turnId,
        UUID ownerToken,
        boolean replay,
        SupportMessageResponse userMessage,
        SupportMessageResponse assistantMessage
) {
}
