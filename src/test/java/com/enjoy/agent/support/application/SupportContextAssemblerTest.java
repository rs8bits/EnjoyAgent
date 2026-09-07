package com.enjoy.agent.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.support.api.response.SupportMessageResponse;
import com.enjoy.agent.support.safety.SupportDlpSafetyHook;
import com.enjoy.agent.support.safety.SupportSafetyHookChain;
import com.enjoy.agent.support.safety.SupportTextSanitizer;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 严格上下文预算是首 Token 延迟和数据最小化的核心约束，单独做回归测试。 */
@ExtendWith(MockitoExtension.class)
class SupportContextAssemblerTest {

    @Mock
    private SupportConversationPersistenceService persistenceService;
    @Mock
    private SupportConversationMemoryService memoryService;
    @Mock
    private ModelGatewayService modelGatewayService;

    private SupportProperties properties;
    private SupportContextAssembler assembler;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        properties = new SupportProperties();
        properties.setRecentMessageLimit(12);
        properties.setMaxInputTokenUpperBound(150);
        SupportTextSanitizer sanitizer = new SupportTextSanitizer();
        assembler = new SupportContextAssembler(
                properties,
                persistenceService,
                memoryService,
                new SupportSafetyHookChain(List.of(new SupportDlpSafetyHook(sanitizer))),
                modelGatewayService,
                new ObjectMapper()
        );
        user = new AuthenticatedUser(
                11L, "user@example.com", "用户", 7L, "tenant-7", "租户 7",
                TenantMemberRole.MEMBER, SystemRole.USER);

        // 测试估算器只按消息字符计数，便于精确验证裁剪策略。生产代码调用的仍是
        // ModelGateway 的 UTF-8 保守 token 上界估算器。
        lenient().when(modelGatewayService.estimateInputTokenUpperBound(
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                anyList(),
                anyList()
        )).thenAnswer(invocation -> {
            List<ModelGatewayConversationMessage> messages = invocation.getArgument(3);
            return messages.stream().mapToInt(message -> message.content().length()).sum();
        });
    }

    @Test
    void overBudgetHistory_shouldRemoveTheWholeOldestTurnAndKeepCurrentQuestion() {
        UUID oldTurnId = UUID.randomUUID();
        UUID currentTurnId = UUID.randomUUID();
        when(persistenceService.latestMessages(eq(user), eq(19L), eq(currentTurnId), eq(12)))
                .thenReturn(List.of(
                        message(1L, oldTurnId, 1, "USER", "问".repeat(80)),
                        message(2L, oldTurnId, 2, "ASSISTANT", "答".repeat(80)),
                        message(3L, currentTurnId, 3, "USER", "当前问题")
                ));

        SupportPromptContext result = assembler.assemble(
                user, 19L, currentTurnId, List.of());

        assertThat(result.messages())
                .extracting(ModelGatewayConversationMessage::content)
                .containsExactly("当前问题");
        assertThat(result.estimatedInputTokenUpperBound()).isEqualTo(4);
    }

    @Test
    void missingCurrentUserMessage_shouldFailBeforeCallingTheModel() {
        UUID currentTurnId = UUID.randomUUID();
        when(persistenceService.latestMessages(eq(user), eq(19L), eq(currentTurnId), eq(12)))
                .thenReturn(List.of(message(
                        1L, UUID.randomUUID(), 1, "ASSISTANT", "孤立历史回答")));

        assertThatThrownBy(() -> assembler.assemble(
                user, 19L, currentTurnId, List.of()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SUPPORT_CURRENT_MESSAGE_MISSING"));
    }

    @Test
    void currentQuestionAloneOverBudget_shouldFailClosed() {
        properties.setMaxInputTokenUpperBound(3);
        UUID currentTurnId = UUID.randomUUID();
        when(persistenceService.latestMessages(eq(user), eq(19L), eq(currentTurnId), eq(12)))
                .thenReturn(List.of(message(1L, currentTurnId, 1, "USER", "四个字符")));

        assertThatThrownBy(() -> assembler.assemble(
                user, 19L, currentTurnId, List.of()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SUPPORT_CONTEXT_BUDGET_EXCEEDED"));
    }

    @Test
    void existingSummary_shouldOnlyKeepMessagesAfterItsWatermark() {
        UUID oldTurnId = UUID.randomUUID();
        UUID currentTurnId = UUID.randomUUID();
        when(memoryService.loadSnapshot(user, 19L))
                .thenReturn(new SupportMemorySnapshot("用户此前询问过 Agent 12。", 2L));
        when(persistenceService.latestMessages(eq(user), eq(19L), eq(currentTurnId), eq(12)))
                .thenReturn(List.of(
                        message(1L, oldTurnId, 1, "USER", "旧问题"),
                        message(2L, oldTurnId, 2, "ASSISTANT", "旧回答"),
                        message(3L, currentTurnId, 3, "USER", "现在怎么样了")
                ));

        SupportPromptContext result = assembler.assemble(
                user, 19L, currentTurnId, List.of());

        assertThat(result.sessionMemorySummary()).contains("用户此前询问过 Agent 12");
        assertThat(result.messages())
                .extracting(ModelGatewayConversationMessage::content)
                .containsExactly("现在怎么样了");
    }

    private SupportMessageResponse message(
            Long id,
            UUID turnId,
            long sequence,
            String role,
            String content
    ) {
        return new SupportMessageResponse(
                id, 19L, turnId, sequence, role, content, Instant.parse("2026-09-02T00:00:00Z"));
    }
}
