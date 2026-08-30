package com.enjoy.agent.billing.application;

import com.enjoy.agent.billing.domain.entity.BillingCreditReservation;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.BillingReservationStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.infrastructure.persistence.BillingCreditReservationRepository;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingCreditReservationService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private final BillingCreditReservationRepository repository;
    private final WalletSupportService walletSupportService;
    private final BillingProperties properties;
    private final Clock clock;

    public BillingCreditReservationService(
            BillingCreditReservationRepository repository,
            WalletSupportService walletSupportService,
            BillingProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.walletSupportService = walletSupportService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public BillingCreditReservation reserve(
            Long userId,
            PreparedModelConfig modelConfig,
            int inputTokenUpperBound
    ) {
        String modelCurrency = requirePlatformCurrency(modelConfig.currency());
        validateTokenBudgets(modelConfig, inputTokenUpperBound);
        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(userId);
        requireWalletCurrency(modelCurrency, wallet.getCurrency());
        if (wallet.getStatus() != UserWalletStatus.ACTIVE) {
            throw new ApiException("WALLET_DISABLED", "Wallet is disabled", HttpStatus.PAYMENT_REQUIRED);
        }

        BigDecimal amount = BillingMoney.normalize(reservationAmount(modelConfig, inputTokenUpperBound));
        BigDecimal reserved = BillingMoney.normalize(nullSafe(wallet.getReservedAmount()));
        BigDecimal available = BillingMoney.subtract(wallet.getBalance(), reserved);
        if (available.compareTo(amount) < 0) {
            throw new ApiException(
                    "BALANCE_INSUFFICIENT",
                    "Wallet available balance is insufficient for an official model reservation",
                    HttpStatus.PAYMENT_REQUIRED
            );
        }
        if (amount.signum() == 0) {
            return null;
        }

        wallet.setReservedAmount(BillingMoney.add(reserved, amount));
        BillingCreditReservation reservation = new BillingCreditReservation();
        reservation.setUserId(userId);
        reservation.setOfficialModelConfigId(modelConfig.officialModelConfigId());
        reservation.setReservedAmount(amount);
        reservation.setStatus(BillingReservationStatus.ACTIVE);
        reservation.setExpiresAt(Instant.now(clock).plus(properties.getReservationTtl()));
        return repository.save(reservation);
    }

    @Transactional
    public LinkedReservation linkCallReservation(
            Long userId,
            Long officialModelConfigId,
            Long reservationId
    ) {
        if (reservationId == null) {
            return null;
        }
        return repository.findByIdForUpdate(reservationId)
                .filter(reservation -> reservation.getStatus() == BillingReservationStatus.ACTIVE)
                .filter(reservation -> Objects.equals(reservation.getUserId(), userId))
                .filter(reservation -> Objects.equals(
                        reservation.getOfficialModelConfigId(),
                        officialModelConfigId
                ))
                .map(reservation -> {
                    reservation.setStatus(BillingReservationStatus.LINKED);
                    reservation.setLinkedAt(Instant.now(clock));
                    return new LinkedReservation(reservation.getId(), reservation.getReservedAmount());
                })
                .orElse(null);
    }

    @Transactional
    public boolean releaseFailedCall(Long userId, Long reservationId) {
        if (reservationId == null) {
            return false;
        }
        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(userId);
        BillingCreditReservation reservation = repository
                .findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null
                || !Objects.equals(reservation.getUserId(), userId)
                || reservation.getStatus() != BillingReservationStatus.ACTIVE) {
            return false;
        }
        releaseFromWallet(wallet, reservation.getReservedAmount());
        reservation.setStatus(BillingReservationStatus.RELEASED);
        reservation.setReleasedAt(Instant.now(clock));
        return true;
    }

    @Transactional
    public BigDecimal consumeReservation(Long reservationId, UserWallet lockedWallet) {
        if (reservationId == null) {
            return BigDecimal.ZERO;
        }
        BillingCreditReservation reservation = repository.findByIdForUpdate(reservationId).orElse(null);
        if (reservation == null
                || (reservation.getStatus() != BillingReservationStatus.ACTIVE
                && reservation.getStatus() != BillingReservationStatus.LINKED)) {
            return BigDecimal.ZERO;
        }
        requireWalletOwner(reservation, lockedWallet);
        BigDecimal reservedAmount = nullSafe(reservation.getReservedAmount());
        releaseFromWallet(lockedWallet, reservedAmount);
        reservation.setStatus(BillingReservationStatus.CONSUMED);
        reservation.setReleasedAt(Instant.now(clock));
        return reservedAmount;
    }

    @Transactional
    public boolean renewActiveReservation(Long reservationId) {
        if (reservationId == null) {
            return false;
        }
        return repository.findByIdForUpdate(reservationId).map(reservation -> {
            if (reservation.getStatus() != BillingReservationStatus.ACTIVE
                    || reservation.getReleasedAt() != null) {
                return false;
            }
            reservation.setExpiresAt(Instant.now(clock).plus(properties.getReservationTtl()));
            return true;
        }).orElse(false);
    }

    @Transactional
    public void releaseReservation(Long reservationId, UserWallet lockedWallet) {
        if (reservationId == null) {
            return;
        }
        repository.findByIdForUpdate(reservationId).ifPresent(reservation -> {
            if (reservation.getStatus() == BillingReservationStatus.CONSUMED
                    || reservation.getStatus() == BillingReservationStatus.RELEASED) {
                return;
            }
            requireWalletOwner(reservation, lockedWallet);
            releaseFromWallet(lockedWallet, reservation.getReservedAmount());
            reservation.setStatus(BillingReservationStatus.RELEASED);
            reservation.setReleasedAt(Instant.now(clock));
        });
    }

    @Transactional
    public boolean releaseClaimedReservation(Long reservationId) {
        BillingCreditReservation snapshot = repository.findById(reservationId).orElse(null);
        if (snapshot == null || snapshot.getStatus() != BillingReservationStatus.RELEASING) {
            return false;
        }
        // Keep the same wallet -> reservation lock order used by failure release and settlement.
        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(snapshot.getUserId());
        BillingCreditReservation reservation = repository.findByIdForUpdate(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != BillingReservationStatus.RELEASING) {
            return false;
        }
        releaseFromWallet(wallet, reservation.getReservedAmount());
        reservation.setStatus(BillingReservationStatus.RELEASED);
        reservation.setReleaseClaimedAt(null);
        reservation.setReleasedAt(Instant.now(clock));
        return true;
    }

    private BigDecimal reservationAmount(PreparedModelConfig modelConfig, int inputTokenUpperBound) {
        int outputTokenBudget = modelConfig.maxTokens() == null
                ? properties.getReservationTokens()
                : Math.max(1, modelConfig.maxTokens());
        BigDecimal inputCost = nullSafe(modelConfig.inputPricePerMillion())
                .multiply(BigDecimal.valueOf(inputTokenUpperBound));
        BigDecimal outputCost = nullSafe(modelConfig.outputPricePerMillion())
                .multiply(BigDecimal.valueOf(outputTokenBudget));
        BigDecimal budgetedCost = inputCost.add(outputCost);
        if (budgetedCost.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal calculated = budgetedCost
                .divide(ONE_MILLION, 6, RoundingMode.UP);
        return calculated.max(properties.getMinimumReservationAmount()).setScale(6, RoundingMode.UP);
    }

    private void validateTokenBudgets(PreparedModelConfig modelConfig, int inputTokenUpperBound) {
        if (inputTokenUpperBound < 1
                || inputTokenUpperBound > properties.getReservationMaxInputTokens()) {
            throw new ApiException(
                    "MODEL_PROMPT_BUDGET_EXCEEDED",
                    "Model prompt token upper bound exceeds the configured billing limit",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
        int outputTokenBudget = modelConfig.maxTokens() == null
                ? properties.getReservationTokens()
                : modelConfig.maxTokens();
        if (outputTokenBudget < 1
                || outputTokenBudget > properties.getReservationMaxOutputTokens()) {
            throw new ApiException(
                    "MODEL_OUTPUT_BUDGET_EXCEEDED",
                    "Model output token upper bound exceeds the configured billing limit",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private String requirePlatformCurrency(String modelCurrencyValue) {
        String modelCurrency = BillingCurrency.normalize(modelCurrencyValue);
        String platformCurrency = BillingCurrency.normalize(properties.getCurrency());
        if (modelCurrency == null
                || platformCurrency == null
                || !BillingCurrency.matches(modelCurrency, platformCurrency)) {
            throw currencyMismatch(
                    "Official model currency does not match the platform billing currency"
            );
        }
        return modelCurrency;
    }

    private void requireWalletCurrency(String modelCurrency, String walletCurrency) {
        if (modelCurrency == null
                || BillingCurrency.normalize(walletCurrency) == null
                || !BillingCurrency.matches(modelCurrency, walletCurrency)) {
            throw currencyMismatch(
                    "Official model currency does not match wallet currency; currency conversion is not supported"
            );
        }
    }

    private ApiException currencyMismatch(String message) {
        return new ApiException("BILLING_CURRENCY_MISMATCH", message, HttpStatus.CONFLICT);
    }

    private void releaseFromWallet(UserWallet wallet, BigDecimal amount) {
        BigDecimal remaining = BillingMoney.subtract(
                nullSafe(wallet.getReservedAmount()),
                nullSafe(amount)
        );
        if (remaining.signum() < 0) {
            throw new ApiException(
                    "BILLING_RESERVED_AMOUNT_INVARIANT_VIOLATION",
                    "Wallet reserved amount is lower than the reservation being released",
                    HttpStatus.CONFLICT
            );
        }
        wallet.setReservedAmount(remaining);
    }

    private void requireWalletOwner(BillingCreditReservation reservation, UserWallet wallet) {
        Long walletUserId = wallet.getUser() == null ? null : wallet.getUser().getId();
        if (!Objects.equals(reservation.getUserId(), walletUserId)) {
            throw new ApiException(
                    "BILLING_RESERVATION_OWNER_MISMATCH",
                    "Billing reservation does not belong to the locked wallet",
                    HttpStatus.CONFLICT
            );
        }
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    public record LinkedReservation(Long id, BigDecimal reservedAmount) {
    }
}
