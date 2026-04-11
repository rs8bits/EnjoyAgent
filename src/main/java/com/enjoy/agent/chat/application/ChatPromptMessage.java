package com.enjoy.agent.chat.application;

import com.enjoy.agent.chat.domain.enums.ChatMessageRole;

/**
 * 发给模型的消息快照。
 */
public record ChatPromptMessage(
        ChatMessageRole role,
        String content
) {
}
