package com.enjoy.agent.billing.application;

import com.enjoy.agent.billing.domain.entity.BillingUsageEvent;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.BillingUsageEventStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.billing.infrastructure.messaging.BillingUsageEventPublisher;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 官方模型使用计费与异步扣费服务。
 */
@Service
public class BillingUsageApplicationService {

    private static final Logger log = LoggerFactory.getLogger(BillingUsageApplicationService.class);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private final BillingUsageEventRepository billingUsageEventRepository;
    private final WalletSupportService walletSupportService;
    private final BillingUsageEventPublisher billingUsageEventPublisher;
    private final BillingProperties billingProperties;
    private final Clock clock;

    public BillingUsageApplicationService(
            BillingUsageEventRepository billingUsageEventRepository,
            WalletSupportService walletSupportService,
            BillingUsageEventPublisher billingUsageEventPublisher,
            BillingProperties billingProperties,
            Clock clock
    ) {
        this.billingUsageEventRepository = billingUsageEventRepository;
        this.walletSupportService = walletSupportService;
        this.billingUsageEventPublisher = billingUsageEventPublisher;
        this.billingProperties = billingProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void assertUserCanUseOfficialModel(Long userId, PreparedModelConfig modelConfig) {
        if (!billingProperties.isEnabled() || modelConfig.officialModelConfigId() == null) {
            return;
        }
        UserWallet wallet = walletSupportService.loadOrCreateWallet(userId);
        if (wallet.getStatus() != UserWalletStatus.ACTIVE) {
            throw new ApiException("WALLET_DISABLED", "Wallet is disabled", HttpStatus.PAYMENT_REQUIRED);
        }
        if (wallet.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(
                    "BALANCE_NEGATIVE",
                    "Wallet balance is negative, please recharge before using official models",
                    HttpStatus.PAYMENT_REQUIRED
            );
        }
    }

    @Transactional
    public void recordUsageEventIfNeeded(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayResult result,
            Long modelCallLogId
    ) {
        PreparedModelConfig modelConfig = preparedChatTurn.modelConfig();
        if (!billingProperties.isEnabled() || modelConfig.officialModelConfigId() == null) {
            return;
        }
        if (billingUsageEventRepository.findByModelCallLogId(modelCallLogId).isPresent()) {
            return;
        }

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
        event.setCalculatedAmount(calculateAmount(
                result.promptTokens(),
                result.completionTokens(),
                modelConfig.inputPricePerMillion(),
                modelConfig.outputPricePerMillion()
        ));
        event.setCurrency(modelConfig.currency() == null ? billingProperties.getCurrency() : modelConfig.currency());
        event.setStatus(BillingUsageEventStatus.PENDING);
        BillingUsageEvent savedEvent = billingUsageEventRepository.saveAndFlush(event);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    billingUsageEventPublisher.publish(savedEvent.getId());
                } catch (RuntimeException ex) {
                    log.error("发布 billing usage event 失败, eventId={}", savedEvent.getId(), ex);
                }
            }
        });
    }

    @Transactional
    public void processUsageEvent(Long eventId) {
        BillingUsageEvent event = billingUsageEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ApiException("BILLING_USAGE_EVENT_NOT_FOUND", "Billing usage event not found", HttpStatus.NOT_FOUND));
        if (event.getStatus() == BillingUsageEventStatus.PROCESSED) {
            return;
        }

        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(event.getUserId());
        if (wallet.getStatus() != UserWalletStatus.ACTIVE) {
            throw new ApiException("WALLET_DISABLED", "Wallet is disabled", HttpStatus.BAD_REQUEST);
        }

        BigDecimal amount = nullSafeAmount(event.getCalculatedAmount());
        wallet.setBalance(wallet.getBalance().subtract(amount));
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
        billingUsageEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUsageEventFailed(Long eventId, RuntimeException ex) {
        billingUsageEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(BillingUsageEventStatus.FAILED);
            event.setErrorMessage(truncate(ex.getMessage(), 1000));
            billingUsageEventRepository.save(event);
        });
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
        return inputAmount.add(outputAmount);
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
