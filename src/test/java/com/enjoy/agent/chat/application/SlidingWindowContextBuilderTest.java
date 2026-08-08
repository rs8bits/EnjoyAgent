package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SlidingWindowContextBuilderTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private SlidingWindowContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SlidingWindowContextBuilder(chatMessageRepository);
    }

    @Test
    void buildWindow_shouldRequestConfiguredLimitAndReturnChronologicalOrder() {
        when(chatMessageRepository.findAllBySession_IdOrderByIdDesc(8L, PageRequest.of(0, 3)))
                .thenReturn(List.of(
                        message(ChatMessageRole.USER, "第三条"),
                        message(ChatMessageRole.ASSISTANT, "第二条"),
                        message(ChatMessageRole.USER, "第一条")
                ));

        List<ChatPromptMessage> result = builder.buildWindow(8L, 3);

        assertThat(result).extracting(ChatPromptMessage::content)
                .containsExactly("第一条", "第二条", "第三条");
        verify(chatMessageRepository).findAllBySession_IdOrderByIdDesc(8L, PageRequest.of(0, 3));
    }

    @Test
    void buildWindow_shouldUseDefaultTenWhenWindowSizeIsNonPositive() {
        when(chatMessageRepository.findAllBySession_IdOrderByIdDesc(8L, PageRequest.of(0, 10)))
                .thenReturn(List.of());

        assertThat(builder.buildWindow(8L, 0)).isEmpty();

        verify(chatMessageRepository).findAllBySession_IdOrderByIdDesc(8L, PageRequest.of(0, 10));
    }

    private ChatMessage message(ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
