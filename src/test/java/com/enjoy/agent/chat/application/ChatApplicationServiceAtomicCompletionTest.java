package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.chat.api.response.ChatTurnResponse;
import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionMemoryRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatTurnRequestRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalService;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelCallLogService;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.modelgateway.infrastructure.persistence.ModelCallLogRepository;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.workflow.application.WorkflowOrchestratorService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ChatApplicationServiceAtomicCompletionTest {

    @Test
    void assistantMessageAndIdempotencyCompletionShareTheSameTransactionBoundary() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatTurnIdempotencyService idempotencyService = mock(ChatTurnIdempotencyService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AtomicBoolean inTransaction = new AtomicBoolean();
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.transaction.support.TransactionCallback<Object> callback = invocation.getArgument(0);
            inTransaction.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                inTransaction.set(false);
            }
        });

        Tenant tenant = new Tenant();
        tenant.setId(1L);
        ChatSession session = new ChatSession();
        session.setId(4L);
        session.setTenant(tenant);
        ChatMessage userMessage = new ChatMessage();
        userMessage.setId(5L);
        userMessage.setTenant(tenant);
        userMessage.setSession(session);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent("hello");
        when(sessionRepository.findById(4L)).thenReturn(Optional.of(session));
        when(messageRepository.findById(5L)).thenReturn(Optional.of(userMessage));
        when(messageRepository.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage assistant = invocation.getArgument(0);
            assertThat(inTransaction).isTrue();
            assistant.setId(6L);
            return assistant;
        });
        ChatTurnIdempotencyService.TurnHandle handle =
                new ChatTurnIdempotencyService.TurnHandle(9L, "owner");
        doAnswer(invocation -> {
            assertThat(inTransaction).isTrue();
            return null;
        }).when(idempotencyService).complete(eq(handle), any(ChatTurnResponse.class));

        ChatApplicationService service = service(
                sessionRepository, messageRepository, transactionTemplate, idempotencyService);
        PreparedModelConfig modelConfig = new PreparedModelConfig(
                CredentialProvider.OPENAI, ModelType.CHAT, "model", CredentialSource.USER,
                7L, "cipher", null, 128);
        PreparedChatTurn turn = new PreparedChatTurn(
                1L, 2L, 3L, 4L, 5L, "hello", "", null, modelConfig,
                null, List.of(), null, null, List.of(), null, List.of());
        ModelGatewayResult result = new ModelGatewayResult(
                "answer", CredentialProvider.OPENAI, "model", CredentialSource.USER,
                7L, 10L, 1, 2, 3);

        ChatTurnResponse response = ReflectionTestUtils.invokeMethod(
                service, "persistAssistantReplyTransactional", turn, result, false, null, handle);

        assertThat(response).isNotNull();
        verify(messageRepository).saveAndFlush(any(ChatMessage.class));
        verify(idempotencyService).complete(eq(handle), eq(response));
    }

    private ChatApplicationService service(
            ChatSessionRepository sessions,
            ChatMessageRepository messages,
            TransactionTemplate transactionTemplate,
            ChatTurnIdempotencyService idempotencyService
    ) {
        return new ChatApplicationService(
                sessions,
                messages,
                mock(AgentRepository.class),
                mock(TenantRepository.class),
                mock(AppUserRepository.class),
                mock(SlidingWindowContextBuilder.class),
                mock(ChatSessionMemoryService.class),
                mock(ChatSessionMemoryRepository.class),
                mock(KnowledgeBaseApplicationService.class),
                mock(KnowledgeRetrievalService.class),
                mock(McpRuntimeService.class),
                mock(McpChatOrchestratorService.class),
                mock(ModelGatewayService.class),
                mock(ModelCallLogService.class),
                mock(ModelCallLogRepository.class),
                mock(McpToolCallLogRepository.class),
                mock(BillingUsageApplicationService.class),
                transactionTemplate,
                Clock.systemUTC(),
                mock(WorkflowOrchestratorService.class),
                idempotencyService,
                new ChatSessionDeletionPolicy(),
                mock(ChatTurnRequestRepository.class)
        );
    }
}
