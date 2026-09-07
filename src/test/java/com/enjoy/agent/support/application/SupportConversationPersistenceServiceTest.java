package com.enjoy.agent.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.support.domain.entity.SupportConversation;
import com.enjoy.agent.support.domain.entity.SupportMessage;
import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import com.enjoy.agent.support.infrastructure.persistence.SupportConversationRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportMessageRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportTurnRepository;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupportConversationPersistenceServiceTest {

    @Mock
    private SupportConversationRepository conversationRepository;
    @Mock
    private SupportTurnRepository turnRepository;
    @Mock
    private SupportMessageRepository messageRepository;

    private SupportConversationPersistenceService service;
    private SupportConversation conversation;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        SupportProperties properties = new SupportProperties();
        properties.setEmitterTimeoutMs(60_000);
        service = new SupportConversationPersistenceService(
                conversationRepository, turnRepository, messageRepository, properties);

        Tenant tenant = new Tenant();
        tenant.setId(7L);
        AppUser appUser = new AppUser();
        appUser.setId(11L);
        conversation = new SupportConversation();
        conversation.setId(19L);
        conversation.setTenant(tenant);
        conversation.setUser(appUser);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        user = new AuthenticatedUser(
                11L, "user@example.com", "用户", 7L, "tenant-7", "租户 7",
                TenantMemberRole.MEMBER, SystemRole.USER);
    }

    @Test
    void failedIdempotentTurn_shouldReuseUserMessageWithoutDuplicatingHistory() {
        when(conversationRepository.findOwnedByIdForUpdate(19L, 7L, 11L))
                .thenReturn(Optional.of(conversation));
        when(turnRepository.findByConversation_IdAndIdempotencyKey(19L, "support_key-1"))
                .thenReturn(Optional.empty());
        when(turnRepository.findFirstByConversation_IdAndStatusOrderByCreatedAtAsc(
                19L, SupportTurnStatus.RUNNING)).thenReturn(Optional.empty());
        when(turnRepository.saveAndFlush(any(SupportTurn.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.saveAndFlush(any(SupportMessage.class))).thenAnswer(invocation -> {
            SupportMessage message = invocation.getArgument(0);
            message.setId(101L);
            message.setCreatedAt(Instant.now());
            return message;
        });

        SupportTurnStart first = service.beginTurn(
                user, 19L, "support_key-1", "查询我的 Agent");

        ArgumentCaptor<SupportTurn> turnCaptor = ArgumentCaptor.forClass(SupportTurn.class);
        ArgumentCaptor<SupportMessage> messageCaptor = ArgumentCaptor.forClass(SupportMessage.class);
        verify(turnRepository).saveAndFlush(turnCaptor.capture());
        verify(messageRepository).saveAndFlush(messageCaptor.capture());
        SupportTurn persistedTurn = turnCaptor.getValue();
        var firstOwnerToken = persistedTurn.getOwnerToken();
        SupportMessage persistedUserMessage = messageCaptor.getValue();
        persistedTurn.setStatus(SupportTurnStatus.FAILED);

        when(turnRepository.findByConversation_IdAndIdempotencyKey(19L, "support_key-1"))
                .thenReturn(Optional.of(persistedTurn));
        when(turnRepository.existsByConversation_IdAndStatus(19L, SupportTurnStatus.RUNNING))
                .thenReturn(false);
        when(messageRepository.findAllByTurn_IdOrderBySequenceAsc(persistedTurn.getId()))
                .thenReturn(List.of(persistedUserMessage));

        SupportTurnStart retry = service.beginTurn(
                user, 19L, "support_key-1", "查询我的 Agent");

        assertThat(retry.replay()).isFalse();
        assertThat(retry.turnId()).isEqualTo(first.turnId());
        assertThat(retry.userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(persistedTurn.getStatus()).isEqualTo(SupportTurnStatus.RUNNING);
        assertThat(persistedTurn.getLeaseExpiresAt()).isAfter(Instant.now());
        assertThat(retry.ownerToken()).isNotEqualTo(firstOwnerToken);
        assertThat(persistedTurn.getOwnerToken()).isEqualTo(retry.ownerToken());
        verify(turnRepository, times(1)).saveAndFlush(any(SupportTurn.class));
        verify(messageRepository, times(1)).saveAndFlush(any(SupportMessage.class));
    }

    @Test
    void staleOwner_shouldNotCompleteTurnAfterLeaseWasTakenOver() {
        UUID turnId = UUID.randomUUID();
        UUID currentOwner = UUID.randomUUID();
        SupportTurn turn = runningTurn(turnId, currentOwner);
        when(conversationRepository.findOwnedByIdForUpdate(19L, 7L, 11L))
                .thenReturn(Optional.of(conversation));
        when(turnRepository.findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                turnId, 7L, 11L)).thenReturn(Optional.of(turn));

        assertThatThrownBy(() -> service.completeTurn(
                user, 19L, turnId, UUID.randomUUID(), "旧线程的回答", null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SUPPORT_TURN_OWNERSHIP_LOST"));

        assertThat(turn.getStatus()).isEqualTo(SupportTurnStatus.RUNNING);
        verify(messageRepository, never()).saveAndFlush(any(SupportMessage.class));
    }

    @Test
    void staleOwnerFailure_shouldNotMarkNewExecutionAsFailed() {
        UUID turnId = UUID.randomUUID();
        SupportTurn turn = runningTurn(turnId, UUID.randomUUID());
        when(turnRepository.findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                turnId, 7L, 11L)).thenReturn(Optional.of(turn));

        service.failTurn(
                user, turnId, UUID.randomUUID(), "SUPPORT_STREAM_DISCONNECTED", "旧连接已断开");

        assertThat(turn.getStatus()).isEqualTo(SupportTurnStatus.RUNNING);
        assertThat(turn.getErrorCode()).isNull();
        assertThat(turn.getErrorMessage()).isNull();
    }

    /** 构造一个归属当前测试租户和用户的运行中 Turn，减少并发测试里的样板代码。 */
    private SupportTurn runningTurn(UUID turnId, UUID ownerToken) {
        SupportTurn turn = new SupportTurn();
        turn.setId(turnId);
        turn.setConversation(conversation);
        turn.setRequestHash("a".repeat(64));
        turn.setIdempotencyKey("support_key-test");
        turn.setOwnerToken(ownerToken);
        turn.setStatus(SupportTurnStatus.RUNNING);
        turn.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        return turn;
    }
}
