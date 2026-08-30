package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.billing.domain.entity.BillingUsageEvent;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.BillingUsageEventStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BillingUsageApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T15:30:00Z");

    private BillingUsageEventRepository eventRepository;
    private WalletSupportService walletSupportService;
    private BillingUsageApplicationService service;
    private BillingProperties properties;
    private BillingCreditReservationService reservationService;
    private BillingUsageOutboxService outboxService;

    @BeforeEach
    void setUp() {
        eventRepository = mock(BillingUsageEventRepository.class);
        walletSupportService = mock(WalletSupportService.class);
        outboxService = mock(BillingUsageOutboxService.class);
        reservationService = mock(BillingCreditReservationService.class);
        properties = new BillingProperties();
        properties.setEnabled(true);
        service = new BillingUsageApplicationService(
                eventRepository,
                walletSupportService,
                outboxService,
                reservationService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void processUsageEvent_shouldDebitHistoricalUsageEvenWhenWalletWasDisabledLater() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        UserWallet wallet = wallet(UserWalletStatus.DISABLED, new BigDecimal("10.000000"));
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        when(walletSupportService.loadOrCreateWalletForUpdate(101L)).thenReturn(wallet);

        service.processUsageEvent(81L);

        assertThat(wallet.getBalance()).isEqualByComparingTo("9.750000");
        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(event.getErrorMessage()).isNull();
        verify(walletSupportService).createTransaction(
                eq(wallet),
                eq(UserWalletTransactionType.MODEL_USAGE_DEBIT),
                argThat(amount -> amount.compareTo(new BigDecimal("-0.250000")) == 0),
                eq(WalletReferenceType.MODEL_CALL_LOG),
                eq(901L),
                eq("官方模型调用扣费")
        );
        verify(eventRepository).save(event);
    }

    @Test
    void beginOfficialModelCall_shouldStillRejectNewUsageForDisabledWallet() {
        PreparedModelConfig modelConfig = mock(PreparedModelConfig.class);
        when(modelConfig.officialModelConfigId()).thenReturn(501L);
        when(reservationService.reserve(101L, modelConfig, 1_000)).thenThrow(new ApiException(
                "WALLET_DISABLED",
                "Wallet is disabled",
                org.springframework.http.HttpStatus.PAYMENT_REQUIRED
        ));

        assertThatThrownBy(() -> service.beginOfficialModelCall(101L, modelConfig, 1_000))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("WALLET_DISABLED");
    }

    @Test
    void obsoletePreflightApiShouldNotExistBecauseItUsedToLeakReservations() {
        assertThat(java.util.Arrays.stream(BillingUsageApplicationService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("assertUserCanUseOfficialModel");
        assertThat(java.util.Arrays.stream(BillingUsageApplicationService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("recordUsageEventIfNeeded"))
                .map(java.lang.reflect.Method::getParameterCount))
                .containsExactly(4);
    }

    @Test
    void processUsageEvent_shouldRemainIdempotentAfterProcessed() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setStatus(BillingUsageEventStatus.PROCESSED);
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));

        service.processUsageEvent(81L);

        verifyNoInteractions(walletSupportService);
        verify(eventRepository, never()).save(any(BillingUsageEvent.class));
    }

    @Test
    void processUsageEvent_shouldNeverOverdrawCreditReservedForOtherCalls() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setReservationId(71L);
        UserWallet wallet = wallet(UserWalletStatus.ACTIVE, new BigDecimal("0.250000"));
        wallet.setReservedAmount(new BigDecimal("0.200000"));
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        when(walletSupportService.loadOrCreateWalletForUpdate(101L)).thenReturn(wallet);
        when(reservationService.consumeReservation(71L, wallet)).thenAnswer(invocation -> {
            wallet.setReservedAmount(new BigDecimal("0.150000"));
            return new BigDecimal("0.050000");
        });

        assertThatThrownBy(() -> service.processUsageEvent(81L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BILLING_SETTLEMENT_CREDIT_SHORTFALL");

        assertThat(wallet.getBalance()).isEqualByComparingTo("0.250000");
        verify(walletSupportService, never()).createTransaction(
                any(), any(), any(), any(), any(), any());
        verify(eventRepository, never()).save(any(BillingUsageEvent.class));
    }

    @Test
    void processUsageEvent_shouldRejectCurrencyMismatchBeforeConsumingReservation() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setCurrency("USD");
        event.setReservationId(71L);
        UserWallet wallet = wallet(UserWalletStatus.ACTIVE, new BigDecimal("1.000000"));
        wallet.setReservedAmount(new BigDecimal("0.250000"));
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        when(walletSupportService.loadOrCreateWalletForUpdate(101L)).thenReturn(wallet);

        assertThatThrownBy(() -> service.processUsageEvent(81L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("BILLING_CURRENCY_MISMATCH");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        assertThat(wallet.getBalance()).isEqualByComparingTo("1.000000");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0.250000");
        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.PENDING);
        verify(reservationService, never()).consumeReservation(any(), any());
        verify(walletSupportService, never()).createTransaction(any(), any(), any(), any(), any(), any());
        verify(eventRepository, never()).save(any(BillingUsageEvent.class));
    }

    @Test
    void markUsageEventFailed_shouldLockAndNeverOverwriteProcessedEvent() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setStatus(BillingUsageEventStatus.PROCESSED);
        event.setProcessedAt(NOW);
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));

        service.markUsageEventFailed(81L, new IllegalStateException("late failure"));

        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(event.getErrorMessage()).isNull();
        verify(eventRepository).findByIdForUpdate(81L);
        verify(eventRepository, never()).save(any(BillingUsageEvent.class));
    }

    @Test
    void markUsageEventFailed_shouldPersistFailureUnderPessimisticLock() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        String longMessage = "x".repeat(1_100);

        service.markUsageEventFailed(81L, new IllegalStateException(longMessage));

        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.FAILED);
        assertThat(event.getErrorMessage()).hasSize(1_000);
        verify(eventRepository).findByIdForUpdate(81L);
        verify(eventRepository).save(event);
    }

    @Test
    void exhaustedAutomaticRetries_shouldKeepLinkedCreditForManualReplay() {
        properties.setProcessingMaxAttempts(1);
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setReservationId(71L);
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));

        assertThat(service.scheduleUsageEventRetry(81L, new IllegalStateException("temporary"))).isFalse();

        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.FAILED);
        verify(reservationService, never()).releaseReservation(any(), any());
        verifyNoInteractions(walletSupportService);

        service.replayFailedUsageEvent(81L);

        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.PENDING);
        verify(outboxService).scheduleProcessingRetry(81L, NOW, "manual admin replay");
        verify(reservationService, never()).releaseReservation(any(), any());
    }

    @Test
    void administratorAbandon_shouldBeTheOnlyTerminalPathThatReleasesFailedCredit() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setStatus(BillingUsageEventStatus.FAILED);
        event.setReservationId(71L);
        UserWallet wallet = wallet(UserWalletStatus.ACTIVE, new BigDecimal("1.000000"));
        wallet.setReservedAmount(new BigDecimal("0.250000"));
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        when(walletSupportService.loadOrCreateWalletForUpdate(101L)).thenReturn(wallet);

        service.abandonFailedUsageEvent(81L);

        assertThat(event.getStatus()).isEqualTo(BillingUsageEventStatus.ABANDONED);
        verify(reservationService).releaseReservation(71L, wallet);
        verify(eventRepository).save(event);
    }

    @Test
    void abandonedEvent_shouldNeverBeDebitedByALateDuplicateDelivery() {
        BillingUsageEvent event = pendingEvent(81L, 901L, new BigDecimal("0.250000"));
        event.setStatus(BillingUsageEventStatus.ABANDONED);
        when(eventRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(event));

        service.processUsageEvent(81L);

        verifyNoInteractions(walletSupportService, reservationService);
    }

    @Test
    void missingOrPartialUsage_shouldSettleConservativelyFromReservationInsteadOfZero() {
        PreparedModelConfig modelConfig = pricedModel();
        PreparedChatTurn turn = mock(PreparedChatTurn.class);
        when(turn.modelConfig()).thenReturn(modelConfig);
        when(turn.userId()).thenReturn(101L);
        when(turn.tenantId()).thenReturn(201L);
        when(turn.agentId()).thenReturn(301L);
        when(turn.sessionId()).thenReturn(401L);
        when(eventRepository.findByModelCallLogId(901L)).thenReturn(Optional.empty());
        when(reservationService.linkCallReservation(101L, 501L, 71L))
                .thenReturn(new BillingCreditReservationService.LinkedReservation(
                        71L, new BigDecimal("0.123456")));
        when(eventRepository.saveAndFlush(any(BillingUsageEvent.class))).thenAnswer(invocation -> {
            BillingUsageEvent event = invocation.getArgument(0);
            event.setId(81L);
            return event;
        });
        ModelGatewayResult result = new ModelGatewayResult(
                "answer",
                CredentialProvider.OPENAI,
                "gpt-test",
                CredentialSource.PLATFORM,
                null,
                50L,
                120,
                null,
                null
        );

        service.recordUsageEventIfNeeded(turn, result, 901L, 71L);

        ArgumentCaptor<BillingUsageEvent> eventCaptor = ArgumentCaptor.forClass(BillingUsageEvent.class);
        verify(eventRepository).saveAndFlush(eventCaptor.capture());
        BillingUsageEvent saved = eventCaptor.getValue();
        assertThat(saved.getCalculatedAmount()).isEqualByComparingTo("0.123456");
        assertThat(saved.getUsageEstimated()).isTrue();
        assertThat(saved.getPromptTokens()).isEqualTo(120);
        assertThat(saved.getCompletionTokens()).isNull();
        assertThat(saved.getReservationId()).isEqualTo(71L);
        verify(outboxService).enqueue(81L, NOW);
    }

    @Test
    void missingUsageWithoutReservation_shouldFailClosedForPricedOfficialModel() {
        PreparedModelConfig modelConfig = pricedModel();
        PreparedChatTurn turn = mock(PreparedChatTurn.class);
        when(turn.modelConfig()).thenReturn(modelConfig);
        when(turn.userId()).thenReturn(101L);
        when(eventRepository.findByModelCallLogId(901L)).thenReturn(Optional.empty());
        when(reservationService.linkCallReservation(101L, 501L, 71L)).thenReturn(null);
        ModelGatewayResult result = new ModelGatewayResult(
                "answer", CredentialProvider.OPENAI, "gpt-test", CredentialSource.PLATFORM,
                null, 50L, null, null, null);

        assertThatThrownBy(() -> service.recordUsageEventIfNeeded(turn, result, 901L, 71L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BILLING_USAGE_MISSING_WITHOUT_RESERVATION");

        verify(eventRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    private BillingUsageEvent pendingEvent(Long eventId, Long modelCallLogId, BigDecimal amount) {
        BillingUsageEvent event = new BillingUsageEvent();
        event.setId(eventId);
        event.setUserId(101L);
        event.setModelCallLogId(modelCallLogId);
        event.setCalculatedAmount(amount);
        event.setCurrency("CNY");
        event.setStatus(BillingUsageEventStatus.PENDING);
        return event;
    }

    private UserWallet wallet(UserWalletStatus status, BigDecimal balance) {
        AppUser user = new AppUser();
        user.setId(101L);
        UserWallet wallet = new UserWallet();
        wallet.setId(201L);
        wallet.setUser(user);
        wallet.setBalance(balance);
        wallet.setReservedAmount(BigDecimal.ZERO);
        wallet.setCurrency("CNY");
        wallet.setStatus(status);
        return wallet;
    }

    private PreparedModelConfig pricedModel() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "gpt-test",
                CredentialSource.PLATFORM,
                null,
                null,
                BigDecimal.ZERO,
                256,
                501L,
                "https://api.openai.com",
                new BigDecimal("2.000000"),
                new BigDecimal("10.000000"),
                "CNY"
        );
    }
}
