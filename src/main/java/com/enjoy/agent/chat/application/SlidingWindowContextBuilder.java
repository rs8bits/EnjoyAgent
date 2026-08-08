package com.enjoy.agent.chat.application;

import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口上下文构建器。
 */
@Component
public class SlidingWindowContextBuilder {

    private final ChatMessageRepository chatMessageRepository;

    public SlidingWindowContextBuilder(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 读取某个会话最近 N 条消息，并按时间正序返回给模型。
     */
    public List<ChatPromptMessage> buildWindow(Long sessionId, Integer windowSize) {
        int safeWindowSize = windowSize == null || windowSize <= 0 ? 10 : windowSize;
        List<ChatMessage> latestMessages = chatMessageRepository.findAllBySession_IdOrderByIdDesc(
                sessionId,
                PageRequest.of(0, safeWindowSize)
        );

        List<ChatPromptMessage> promptMessages = new ArrayList<>(latestMessages.size());
        for (ChatMessage message : latestMessages) {
            promptMessages.add(new ChatPromptMessage(message.getRole(), message.getContent()));
        }
        Collections.reverse(promptMessages);
        return promptMessages;
    }
}
