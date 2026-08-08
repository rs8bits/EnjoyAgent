package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.chat.domain.entity.ChatSessionMemory;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionMemoryRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.shared.config.CacheNames;
import com.enjoy.agent.shared.config.CacheProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ChatSessionMemoryServiceCacheTest.TestConfig.class)
class ChatSessionMemoryServiceCacheTest {

    @Autowired
    private ChatSessionMemoryService service;

    @Autowired
    private ChatSessionMemoryRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheProperties cacheProperties;

    @BeforeEach
    void setUp() {
        reset(repository);
        cacheProperties.setSessionSummaryEnabled(true);
        var cache = cacheManager.getCache(CacheNames.SESSION_SUMMARY);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void loadSummary_shouldCachePerTenant() {
        when(repository.findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY))
                .thenReturn(Optional.of(memory("租户七摘要")));
        when(repository.findBySession_IdAndTenant_IdAndMemoryType(8L, 9L, MemoryStrategy.SESSION_SUMMARY))
                .thenReturn(Optional.of(memory("租户九摘要")));

        assertThat(service.loadSummary(7L, 8L, MemoryStrategy.SESSION_SUMMARY)).isEqualTo("租户七摘要");
        assertThat(service.loadSummary(7L, 8L, MemoryStrategy.SESSION_SUMMARY)).isEqualTo("租户七摘要");
        assertThat(service.loadSummary(9L, 8L, MemoryStrategy.SESSION_SUMMARY)).isEqualTo("租户九摘要");

        verify(repository, times(1))
                .findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY);
        verify(repository, times(1))
                .findBySession_IdAndTenant_IdAndMemoryType(8L, 9L, MemoryStrategy.SESSION_SUMMARY);
    }

    @Test
    void loadSummary_shouldBypassCacheWhenDisabled() {
        cacheProperties.setSessionSummaryEnabled(false);
        when(repository.findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY))
                .thenReturn(Optional.of(memory("摘要")));

        service.loadSummary(7L, 8L, MemoryStrategy.SESSION_SUMMARY);
        service.loadSummary(7L, 8L, MemoryStrategy.SESSION_SUMMARY);

        verify(repository, times(2))
                .findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY);
    }

    private ChatSessionMemory memory(String summary) {
        ChatSessionMemory memory = new ChatSessionMemory();
        memory.setSummary(summary);
        return memory;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheNames.SESSION_SUMMARY);
        }

        @Bean
        CacheProperties cacheProperties() {
            return new CacheProperties();
        }

        @Bean
        ChatSessionMemoryRepository chatSessionMemoryRepository() {
            return mock(ChatSessionMemoryRepository.class);
        }

        @Bean
        ChatSessionRepository chatSessionRepository() {
            return mock(ChatSessionRepository.class);
        }

        @Bean
        ChatMessageRepository chatMessageRepository() {
            return mock(ChatMessageRepository.class);
        }

        @Bean
        ModelGatewayService modelGatewayService() {
            return mock(ModelGatewayService.class);
        }

        @Bean
        TransactionTemplate transactionTemplate() {
            return mock(TransactionTemplate.class);
        }

        @Bean
        ChatSessionMemoryService chatSessionMemoryService(
                ChatSessionMemoryRepository memoryRepository,
                ChatSessionRepository sessionRepository,
                ChatMessageRepository messageRepository,
                ModelGatewayService modelGatewayService,
                TransactionTemplate transactionTemplate,
                CacheManager cacheManager
        ) {
            return new ChatSessionMemoryService(
                    memoryRepository,
                    sessionRepository,
                    messageRepository,
                    modelGatewayService,
                    transactionTemplate,
                    cacheManager
            );
        }
    }
}
