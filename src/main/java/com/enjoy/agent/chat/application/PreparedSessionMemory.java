package com.enjoy.agent.chat.application;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;

/**
 * 聊天运行时的会话记忆快照。
 */
public record PreparedSessionMemory(
        boolean enabled,
        MemoryStrategy strategy,
        Integer updateMessageThreshold,
        String summary
) {
}
