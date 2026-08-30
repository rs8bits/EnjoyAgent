package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.billing.domain.entity.BillingCreditReservation;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.BillingReservationStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.infrastructure.persistence.BillingCreditReservationRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
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

class BillingCreditReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private BillingCreditReservationRepository repository;
    private WalletSupportService walletSupportService;
    private BillingCreditReservationService service;
    private BillingProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(BillingCreditReservationRepository.class);
        walletSupportService = mock(WalletSupportService.class);
        properties = new BillingProperties();
        service = new BillingCreditReservationService(
                repository,
                walletSupportService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            BillingCreditReservation reservation = invocation.getArgument(0);
            reservation.setId(91L);
            return reservation;
        });
    }

    @Test
    void reserve_shouldPriceInputAndOutputBudgetsSeparately() {
        UserWallet wallet = wallet("1.000000", "0.000000");
        when(walletSupportService.loadOrCreateWalletForUpdate(7L)).thenReturn(wallet);

        BillingCreditReservation reservation = service.reserve(
                7L, pricedModel(200), 1_000);

        // input: 1000 * 2 / 1m = .002; output: 200 * 10 / 1m = .002
        assertThat(reservation.getReservedAmount()).isEqualByComparingTo("0.004000");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0.004000");
        assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plus(properties.getReservationTtl()));
    }

    @Test
    void reserve_shouldPriceTheCallSpecificPromptUpperBound() {
        UserWallet wallet = wallet("1.000000", "0.000000");
        when(walletSupportService.loadOrCreateWalletForUpdate(7L)).thenReturn(wallet);

        BillingCreditReservation reservation = service.reserve(7L, pricedModel(200), 1_500);

        // input: 1500 * 2 / 1m = .003; output: 200 * 10 / 1m = .002
        assertThat(reservation.getReservedAmount()).isEqualByComparingTo("0.005000");
    }

    @Test
    void reserve_shouldRejectPromptUpperBoundAboveConfiguredHardCapBeforeLockingWallet() {
        properties.setReservationMaxInputTokens(2_000);

        assertThatThrownBy(() -> service.reserve(7L, pricedModel(200), 2_001))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MODEL_PROMPT_BUDGET_EXCEEDED");

        verify(walletSupportService, org.mockito.Mockito.never()).loadOrCreateWalletForUpdate(7L);
    }

    @Test
    void reserve_shouldRejectModelCurrencyOutsidePlatformBillingCurrencyBeforeLockingWallet() {
        assertThatThrownBy(() -> service.reserve(7L, pricedModel(200, "USD"), 1_000))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("BILLING_CURRENCY_MISMATCH");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        verify(walletSupportService, org.mockito.Mockito.never()).loadOrCreateWalletForUpdate(7L);
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reserve_shouldRejectWalletCurrencyWithoutChangingReservedCredit() {
        UserWallet wallet = wallet("1.000000", "0.125000");
        wallet.setCurrency("USD");
        when(walletSupportService.loadOrCreateWalletForUpdate(7L)).thenReturn(wallet);

        assertThatThrownBy(() -> service.reserve(7L, pricedModel(200), 1_000))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("BILLING_CURRENCY_MISMATCH");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0.125000");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reserve_shouldRejectZeroAvailableBalanceBeforeNetworkCall() {
        when(walletSupportService.loadOrCreateWalletForUpdate(7L))
                .thenReturn(wallet("0.000000", "0.000000"));

        assertThatThrownBy(() -> service.reserve(
                7L, pricedModel(200), 1_000))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BALANCE_INSUFFICIENT");
    }

    @Test
    void reserve_shouldNotSpendCreditAlreadyReservedByAnotherCall() {
        when(walletSupportService.loadOrCreateWalletForUpdate(7L))
                .thenReturn(wallet("0.005000", "0.002000"));

        assertThatThrownBy(() -> service.reserve(
                7L, pricedModel(200), 1_000))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BALANCE_INSUFFICIENT");

        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedCall_shouldReleaseOnlyItsExactReservation() {
        UserWallet wallet = wallet("1.000000", "0.004000");
        BillingCreditReservation reservation = new BillingCreditReservation();
        reservation.setId(91L);
        reservation.setUserId(7L);
        reservation.setReservedAmount(new BigDecimal("0.004000"));
        reservation.setStatus(BillingReservationStatus.ACTIVE);
        when(walletSupportService.loadOrCreateWalletForUpdate(7L)).thenReturn(wallet);
        when(repository.findByIdForUpdate(91L)).thenReturn(Optional.of(reservation));

        assertThat(service.releaseFailedCall(7L, 91L)).isTrue();

        assertThat(wallet.getReservedAmount()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(BillingReservationStatus.RELEASED);
        verify(repository).findByIdForUpdate(91L);
    }

    @Test
    void twoReapers_shouldReleaseAClaimedReservationOnlyOnce() {
        UserWallet wallet = wallet("1.000000", "0.004000");
        BillingCreditReservation reservation = new BillingCreditReservation();
        reservation.setId(91L);
        reservation.setUserId(7L);
        reservation.setReservedAmount(new BigDecimal("0.004000"));
        reservation.setStatus(BillingReservationStatus.RELEASING);
        when(repository.findById(91L)).thenReturn(Optional.of(reservation));
        when(repository.findByIdForUpdate(91L)).thenReturn(Optional.of(reservation));
        when(walletSupportService.loadOrCreateWalletForUpdate(7L)).thenReturn(wallet);

        assertThat(service.releaseClaimedReservation(91L)).isTrue();
        assertThat(service.releaseClaimedReservation(91L)).isFalse();

        assertThat(wallet.getReservedAmount()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(BillingReservationStatus.RELEASED);
    }

    @Test
    void consume_shouldRejectReservationOwnedByAnotherWallet() {
        UserWallet wallet = wallet("1.000000", "0.004000");
        BillingCreditReservation reservation = new BillingCreditReservation();
        reservation.setId(91L);
        reservation.setUserId(8L);
        reservation.setReservedAmount(new BigDecimal("0.004000"));
        reservation.setStatus(BillingReservationStatus.LINKED);
        when(repository.findByIdForUpdate(91L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.consumeReservation(91L, wallet))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BILLING_RESERVATION_OWNER_MISMATCH");

        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0.004000");
        assertThat(reservation.getStatus()).isEqualTo(BillingReservationStatus.LINKED);
    }

    private PreparedModelConfig pricedModel(int maxTokens) {
        return pricedModel(maxTokens, "CNY");
    }

    private PreparedModelConfig pricedModel(int maxTokens, String currency) {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "gpt-test",
                CredentialSource.PLATFORM,
                null,
                null,
                BigDecimal.ZERO,
                maxTokens,
                51L,
                "https://api.openai.com",
                new BigDecimal("2.000000"),
                new BigDecimal("10.000000"),
                currency
        );
    }

    private UserWallet wallet(String balance, String reserved) {
        AppUser user = new AppUser();
        user.setId(7L);
        UserWallet wallet = new UserWallet();
        wallet.setUser(user);
        wallet.setBalance(new BigDecimal(balance));
        wallet.setReservedAmount(new BigDecimal(reserved));
        wallet.setCurrency("CNY");
        wallet.setStatus(UserWalletStatus.ACTIVE);
        return wallet;
    }
}
