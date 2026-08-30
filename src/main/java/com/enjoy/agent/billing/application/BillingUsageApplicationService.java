package com.enjoy.agent.billing.application;

import com.enjoy.agent.billing.domain.entity.BillingUsageEvent;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.BillingUsageEventStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 官方模型使用计费与异步扣费服务。
 */
@Service
public class BillingUsageApplicationService {

    private static final Logger log = LoggerFactory.getLogger(BillingUsageApplicationService.class);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private final BillingUsageEventRepository billingUsageEventRepository;
    private final WalletSupportService walletSupportService;
    private final BillingUsageOutboxService billingUsageOutboxService;
    private final BillingCreditReservationService reservationService;
    private final BillingProperties billingProperties;
    private final Clock clock;
    private final Set<Long> activeReservationIds = ConcurrentHashMap.newKeySet();

    public BillingUsageApplicationService(
            BillingUsageEventRepository billingUsageEventRepository,
            WalletSupportService walletSupportService,
            BillingUsageOutboxService billingUsageOutboxService,
            BillingCreditReservationService reservationService,
            BillingProperties billingProperties,
            Clock clock
    ) {
        this.billingUsageEventRepository = billingUsageEventRepository;
        this.walletSupportService = walletSupportService;
        this.billingUsageOutboxService = billingUsageOutboxService;
        this.reservationService = reservationService;
        this.billingProperties = billingProperties;
        this.clock = clock;
    }

    @Transactional
    public Long beginOfficialModelCall(
            Long userId,
            PreparedModelConfig modelConfig,
            int inputTokenUpperBound
    ) {
        if (!billingProperties.isEnabled() || modelConfig.officialModelConfigId() == null) {
            return null;
        }
        var reservation = reservationService.reserve(userId, modelConfig, inputTokenUpperBound);
        if (reservation == null) {
            return null;
        }
        activeReservationIds.add(reservation.getId());
        return reservation.getId();
    }

    @Transactional
    public boolean releaseOfficialModelCall(Long userId, Long reservationId) {
        if (!billingProperties.isEnabled() || reservationId == null) {
            return false;
        }
        try {
            return reservationService.releaseFailedCall(userId, reservationId);
        } finally {
            activeReservationIds.remove(reservationId);
        }
    }

    @Transactional
    public void recordUsageEventIfNeeded(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayResult result,
            Long modelCallLogId,
            Long reservationId
    ) {
        PreparedModelConfig modelConfig = preparedChatTurn.modelConfig();
        if (!billingProperties.isEnabled() || modelConfig.officialModelConfigId() == null) {
            return;
        }
        BillingUsageEvent existingEvent = billingUsageEventRepository.findByModelCallLogId(modelCallLogId)
                .orElse(null);
        if (existingEvent != null) {
            // A retried success callback may have created a fresh reservation for a model-call log
            // that was already durably accounted. Never leave that unrelated reservation stranded.
            if (reservationId != null
                    && !Objects.equals(existingEvent.getReservationId(), reservationId)) {
                reservationService.releaseFailedCall(preparedChatTurn.userId(), reservationId);
            }
            if (reservationId != null) {
                activeReservationIds.remove(reservationId);
            }
            return;
        }

        BillingCreditReservationService.LinkedReservation linkedReservation;
        try {
            linkedReservation = reservationService.linkCallReservation(
                    preparedChatTurn.userId(),
                    modelConfig.officialModelConfigId(),
                    reservationId
            );
        } finally {
            if (reservationId != null) {
                activeReservationIds.remove(reservationId);
            }
        }
        boolean usageEstimated = !hasCompleteUsage(result);
        BigDecimal calculatedAmount = usageEstimated
                ? fallbackAmountForMissingUsage(modelConfig, linkedReservation)
                : calculateAmount(
                        result.promptTokens(),
                        result.completionTokens(),
                        modelConfig.inputPricePerMillion(),
                        modelConfig.outputPricePerMillion()
                );

        BillingUsageEvent event = new BillingUsageEvent();
        event.setUserId(preparedChatTurn.userId());
        event.setTenantId(preparedChatTurn.tenantId());
        event.setAgentId(preparedChatTurn.agentId());
        event.setSessionId(preparedChatTurn.sessionId());
        event.setModelCallLogId(modelCallLogId);
        event.setOfficialModelConfigId(modelConfig.officialModelConfigId());
        event.setModelName(result.modelName());
        event.setPromptTokens(result.promptTokens());
        event.setCompletionTokens(result.completionTokens());
        event.setInputPricePerMillion(nullSafeAmount(modelConfig.inputPricePerMillion()));
        event.setOutputPricePerMillion(nullSafeAmount(modelConfig.outputPricePerMillion()));
        event.setCalculatedAmount(calculatedAmount);
        event.setUsageEstimated(usageEstimated);
        event.setCurrency(BillingCurrency.normalize(
                modelConfig.currency() == null ? billingProperties.getCurrency() : modelConfig.currency()
        ));
        event.setStatus(BillingUsageEventStatus.PENDING);
        event.setProcessingAttempts(0);
        event.setReservationId(linkedReservation == null ? null : linkedReservation.id());
        BillingUsageEvent savedEvent = billingUsageEventRepository.saveAndFlush(event);
        billingUsageOutboxService.enqueue(savedEvent.getId(), Instant.now(clock));
    }

    @Transactional
    public void processUsageEvent(Long eventId) {
        BillingUsageEvent event = billingUsageEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ApiException("BILLING_USAGE_EVENT_NOT_FOUND", "Billing usage event not found", HttpStatus.NOT_FOUND));
        if (event.getStatus() != BillingUsageEventStatus.PENDING) {
            return;
        }

        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(event.getUserId());
        requireSettlementCurrency(event.getCurrency(), wallet.getCurrency());
        BigDecimal amount = nullSafeAmount(event.getCalculatedAmount());
        BigDecimal reservedAmount = nullSafeAmount(
                reservationService.consumeReservation(event.getReservationId(), wallet));
        BigDecimal balanceAfter = BillingMoney.subtract(wallet.getBalance(), amount);
        if (amount.compareTo(reservedAmount) > 0) {
            log.warn(
                    "Billing usage exceeded reservation, eventId={}, userId={}, reservationId={}, reserved={}, actual={}",
                    event.getId(), event.getUserId(), event.getReservationId(), reservedAmount, amount
            );
        }
        if (balanceAfter.compareTo(nullSafeAmount(wallet.getReservedAmount())) < 0) {
            // The settlement transaction rolls back, including reservation consumption. This can
            // only happen when actual prompt usage exceeded the configured reservation ceiling or
            // when legacy/unreserved events are replayed; financial state must never overdraw.
            throw new ApiException(
                    "BILLING_SETTLEMENT_CREDIT_SHORTFALL",
                    "Wallet credit is insufficient to settle model usage without overdrawing reserved funds",
                    HttpStatus.CONFLICT
            );
        }
        wallet.setBalance(balanceAfter);
        walletSupportService.createTransaction(
                wallet,
                UserWalletTransactionType.MODEL_USAGE_DEBIT,
                amount.negate(),
                WalletReferenceType.MODEL_CALL_LOG,
                event.getModelCallLogId(),
                "官方模型调用扣费"
        );

        event.setStatus(BillingUsageEventStatus.PROCESSED);
        event.setProcessedAt(Instant.now(clock));
        event.setErrorMessage(null);
        event.setNextRetryAt(null);
        billingUsageEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleUsageEventRetry(Long eventId, RuntimeException ex) {
        BillingUsageEvent event = billingUsageEventRepository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() == BillingUsageEventStatus.PROCESSED) {
            return false;
        }
        int attempts = Math.max(0, event.getProcessingAttempts() == null ? 0 : event.getProcessingAttempts()) + 1;
        event.setProcessingAttempts(attempts);
        event.setErrorMessage(truncate(ex.getMessage(), 1000));
        if (attempts >= billingProperties.getProcessingMaxAttempts()) {
            event.setStatus(BillingUsageEventStatus.FAILED);
            // The upstream call has already happened. Keep its LINKED credit frozen while the
            // event awaits an administrator replay; otherwise the user could spend those funds
            // and make an already-incurred provider cost impossible to settle.
            return false;
        }
        Instant retryAt = Instant.now(clock).plus(billingProperties.getProcessingRetryDelay());
        event.setStatus(BillingUsageEventStatus.PENDING);
        event.setNextRetryAt(retryAt);
        billingUsageOutboxService.scheduleProcessingRetry(eventId, retryAt, ex.getMessage());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUsageEventFailed(Long eventId, RuntimeException ex) {
        billingUsageEventRepository.findByIdForUpdate(eventId).ifPresent(event -> {
            // 处理事务已成功提交时，迟到的失败分支不得把财务事实倒退为 FAILED。
            if (event.getStatus() == BillingUsageEventStatus.PROCESSED
                    || event.getStatus() == BillingUsageEventStatus.ABANDONED) {
                return;
            }
            event.setStatus(BillingUsageEventStatus.FAILED);
            event.setErrorMessage(truncate(ex.getMessage(), 1000));
            billingUsageEventRepository.save(event);
        });
    }

    public int renewActiveCallReservations() {
        int renewed = 0;
        for (Long reservationId : List.copyOf(activeReservationIds)) {
            if (reservationService.renewActiveReservation(reservationId)) {
                renewed++;
            } else {
                activeReservationIds.remove(reservationId);
            }
        }
        return renewed;
    }

    @Transactional
    public void replayFailedUsageEvent(Long eventId) {
        BillingUsageEvent event = billingUsageEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ApiException(
                        "BILLING_USAGE_EVENT_NOT_FOUND",
                        "Billing usage event not found",
                        HttpStatus.NOT_FOUND
                ));
        if (event.getStatus() == BillingUsageEventStatus.PROCESSED) {
            return;
        }
        if (event.getStatus() != BillingUsageEventStatus.FAILED) {
            throw new ApiException(
                    "BILLING_USAGE_EVENT_REPLAY_NOT_ALLOWED",
                    "Only failed billing usage events can be replayed",
                    HttpStatus.CONFLICT
            );
        }
        Instant availableAt = Instant.now(clock);
        event.setStatus(BillingUsageEventStatus.PENDING);
        event.setProcessingAttempts(0);
        event.setNextRetryAt(availableAt);
        event.setErrorMessage(null);
        billingUsageOutboxService.scheduleProcessingRetry(eventId, availableAt, "manual admin replay");
    }

    /**
     * Explicitly writes off a failed usage event and releases its held credit. This is deliberately
     * separate from retry exhaustion because releasing automatically would break replay safety.
     */
    @Transactional
    public void abandonFailedUsageEvent(Long eventId) {
        BillingUsageEvent event = billingUsageEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ApiException(
                        "BILLING_USAGE_EVENT_NOT_FOUND",
                        "Billing usage event not found",
                        HttpStatus.NOT_FOUND
                ));
        if (event.getStatus() == BillingUsageEventStatus.ABANDONED) {
            return;
        }
        if (event.getStatus() != BillingUsageEventStatus.FAILED) {
            throw new ApiException(
                    "BILLING_USAGE_EVENT_ABANDON_NOT_ALLOWED",
                    "Only failed billing usage events can be abandoned",
                    HttpStatus.CONFLICT
            );
        }
        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(event.getUserId());
        reservationService.releaseReservation(event.getReservationId(), wallet);
        event.setStatus(BillingUsageEventStatus.ABANDONED);
        event.setNextRetryAt(null);
        event.setErrorMessage("Billing usage charge explicitly abandoned by an administrator");
        billingUsageEventRepository.save(event);
    }

    private BigDecimal calculateAmount(
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion
    ) {
        BigDecimal inputAmount = nullSafeAmount(inputPricePerMillion)
                .multiply(BigDecimal.valueOf(promptTokens == null ? 0L : promptTokens.longValue()))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputAmount = nullSafeAmount(outputPricePerMillion)
                .multiply(BigDecimal.valueOf(completionTokens == null ? 0L : completionTokens.longValue()))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return BillingMoney.normalize(inputAmount.add(outputAmount));
    }

    private void requireSettlementCurrency(String eventCurrency, String walletCurrency) {
        if (!BillingCurrency.matches(eventCurrency, walletCurrency)) {
            throw new ApiException(
                    "BILLING_CURRENCY_MISMATCH",
                    "Billing usage event currency does not match wallet currency; currency conversion is not supported",
                    HttpStatus.CONFLICT
            );
        }
    }

    private boolean hasCompleteUsage(ModelGatewayResult result) {
        return result.promptTokens() != null
                && result.promptTokens() >= 0
                && result.completionTokens() != null
                && result.completionTokens() >= 0;
    }

    private BigDecimal fallbackAmountForMissingUsage(
            PreparedModelConfig modelConfig,
            BillingCreditReservationService.LinkedReservation linkedReservation
    ) {
        if (linkedReservation != null) {
            return nullSafeAmount(linkedReservation.reservedAmount());
        }
        boolean priced = nullSafeAmount(modelConfig.inputPricePerMillion()).signum() > 0
                || nullSafeAmount(modelConfig.outputPricePerMillion()).signum() > 0;
        if (priced) {
            throw new ApiException(
                    "BILLING_USAGE_MISSING_WITHOUT_RESERVATION",
                    "Official model usage is missing and no reservation is available for conservative settlement",
                    HttpStatus.CONFLICT
            );
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal nullSafeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
