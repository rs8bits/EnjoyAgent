package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SlidingWindowContextBuilderProxyTest.TestConfig.class)
class SlidingWindowContextBuilderProxyTest {

    @Autowired
    private SlidingWindowContextBuilder builder;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @BeforeEach
    void setUp() {
        reset(chatMessageRepository);
    }

    @Test
    void buildWindow_shouldQueryAgainAndObserveNewMessages() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(chatMessageRepository.findAllBySession_IdOrderByIdDesc(8L, pageRequest))
                .thenReturn(
                        List.of(message(ChatMessageRole.USER, "第一条")),
                        List.of(
                                message(ChatMessageRole.ASSISTANT, "第二条"),
                                message(ChatMessageRole.USER, "第一条")
                        )
                );

        assertThat(builder.buildWindow(8L, 10)).extracting(ChatPromptMessage::content)
                .containsExactly("第一条");
        assertThat(builder.buildWindow(8L, 10)).extracting(ChatPromptMessage::content)
                .containsExactly("第一条", "第二条");

        verify(chatMessageRepository, times(2))
                .findAllBySession_IdOrderByIdDesc(8L, pageRequest);
    }

    private ChatMessage message(ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        ChatMessageRepository chatMessageRepository() {
            return mock(ChatMessageRepository.class);
        }

        @Bean
        SlidingWindowContextBuilder slidingWindowContextBuilder(ChatMessageRepository repository) {
            return new SlidingWindowContextBuilder(repository);
        }
    }
}
