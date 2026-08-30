package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.chat.domain.entity.ChatSessionMemory;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionMemoryRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelCallAccountingService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ChatSessionMemoryServiceTest {

    @Mock
    private ChatSessionMemoryRepository chatSessionMemoryRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ModelGatewayService modelGatewayService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache summaryCache;

    @Mock private BillingUsageApplicationService billingUsageApplicationService;
    @Mock private ModelCallAccountingService modelCallAccountingService;

    private ChatSessionMemoryService chatSessionMemoryService;

    @BeforeEach
    void setUp() {
        chatSessionMemoryService = new ChatSessionMemoryService(
                chatSessionMemoryRepository,
                chatSessionRepository,
                chatMessageRepository,
                modelGatewayService,
                transactionTemplate,
                cacheManager,
                billingUsageApplicationService,
                modelCallAccountingService
        );
    }

    @Test
    void refreshSummaryIfNeeded_shouldPersistSummaryWhenThresholdReached() {
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        PreparedSessionMemory preparedSessionMemory = new PreparedSessionMemory(true, MemoryStrategy.SESSION_SUMMARY, 4, null);
        PreparedModelConfig chatModelConfig = new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                CredentialSource.USER,
                1L,
                "cipher",
                null,
                512
        );

        when(chatSessionMemoryRepository.findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY))
                .thenReturn(Optional.empty());
        when(chatMessageRepository.findAllBySession_IdOrderByIdAsc(8L))
                .thenReturn(List.of(
                        chatMessage(101L, ChatMessageRole.USER, "第一问"),
                        chatMessage(102L, ChatMessageRole.ASSISTANT, "第一答"),
                        chatMessage(103L, ChatMessageRole.USER, "第二问"),
                        chatMessage(104L, ChatMessageRole.ASSISTANT, "第二答")
                ));
        ModelGatewayChatCompletion completion = new ModelGatewayChatCompletion(
                "- 用户目标\n- 了解系统现状", List.of(), CredentialProvider.OPENAI, "qwen-plus",
                CredentialSource.USER, 1L, 20L, 10, 5, 15);
        when(modelGatewayService.generateChatCompletion(
                eq(chatModelConfig), any(), any(), any(), any(), any())).thenReturn(completion);
        when(chatSessionRepository.findByIdAndTenant_Id(8L, 7L)).thenReturn(Optional.of(chatSession()));
        when(chatSessionMemoryRepository.save(any(ChatSessionMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache("session-summary-v3")).thenReturn(summaryCache);

        PreparedChatTurn turn = preparedTurn(preparedSessionMemory, chatModelConfig);
        chatSessionMemoryService.refreshSummaryIfNeeded(turn);

        ArgumentCaptor<ChatSessionMemory> memoryCaptor = ArgumentCaptor.forClass(ChatSessionMemory.class);
        verify(chatSessionMemoryRepository).save(memoryCaptor.capture());
        ChatSessionMemory savedMemory = memoryCaptor.getValue();
        assertThat(savedMemory.getSummary()).isEqualTo("- 用户目标\n- 了解系统现状");
        assertThat(savedMemory.getMemoryType()).isEqualTo(MemoryStrategy.SESSION_SUMMARY);
        assertThat(savedMemory.getLastMessageId()).isEqualTo(104L);
        assertThat(savedMemory.getVersion()).isEqualTo(1);
        verify(summaryCache).evict("7:8:SESSION_SUMMARY");
        verify(modelCallAccountingService).recordSuccess(eq(turn), eq(completion), any());
    }

    @Test
    void refreshSummaryIfNeeded_shouldSkipWhenThresholdNotReached() {
        PreparedSessionMemory preparedSessionMemory = new PreparedSessionMemory(true, MemoryStrategy.SESSION_SUMMARY, 6, null);

        when(chatSessionMemoryRepository.findBySession_IdAndTenant_IdAndMemoryType(8L, 7L, MemoryStrategy.SESSION_SUMMARY))
                .thenReturn(Optional.empty());
        when(chatMessageRepository.findAllBySession_IdOrderByIdAsc(8L))
                .thenReturn(List.of(
                        chatMessage(101L, ChatMessageRole.USER, "第一问"),
                        chatMessage(102L, ChatMessageRole.ASSISTANT, "第一答")
                ));

        chatSessionMemoryService.refreshSummaryIfNeeded(
                preparedTurn(preparedSessionMemory, preparedChatModelConfig()));

        verify(modelGatewayService, never()).generateChatCompletion(
                any(), any(), any(), any(), any(), any());
        verify(chatSessionMemoryRepository, never()).save(any());
    }

    private PreparedModelConfig preparedChatModelConfig() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                CredentialSource.USER,
                1L,
                "cipher",
                null,
                512
        );
    }

    private PreparedChatTurn preparedTurn(
            PreparedSessionMemory memory,
            PreparedModelConfig modelConfig
    ) {
        return new PreparedChatTurn(
                7L, 2L, 3L, 8L, 104L, "第二问", "", memory, modelConfig,
                null, List.of(), null, null, List.of(), null, List.of());
    }

    private ChatSession chatSession() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);

        ChatSession session = new ChatSession();
        session.setId(8L);
        session.setTenant(tenant);
        return session;
    }

    private ChatMessage chatMessage(Long id, ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
