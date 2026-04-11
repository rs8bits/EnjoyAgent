package com.enjoy.agent.billing.application;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.api.response.UserWalletResponse;
import com.enjoy.agent.billing.api.response.UserWalletTransactionResponse;
import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.entity.UserWalletTransaction;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.billing.infrastructure.persistence.UserWalletRepository;
import com.enjoy.agent.billing.infrastructure.persistence.UserWalletTransactionRepository;
import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 钱包通用逻辑，供用户、管理员和异步扣费链复用。
 */
@Service
public class WalletSupportService {

    private final AppUserRepository appUserRepository;
    private final UserWalletRepository userWalletRepository;
    private final UserWalletTransactionRepository userWalletTransactionRepository;
    private final BillingProperties billingProperties;

    public WalletSupportService(
            AppUserRepository appUserRepository,
            UserWalletRepository userWalletRepository,
            UserWalletTransactionRepository userWalletTransactionRepository,
            BillingProperties billingProperties
    ) {
        this.appUserRepository = appUserRepository;
        this.userWalletRepository = userWalletRepository;
        this.userWalletTransactionRepository = userWalletTransactionRepository;
        this.billingProperties = billingProperties;
    }

    @Transactional
    public UserWallet loadOrCreateWallet(Long userId) {
        return userWalletRepository.findByUser_Id(userId)
                .orElseGet(() -> createWallet(requireUser(userId)));
    }

    @Transactional
    public UserWallet loadOrCreateWalletForUpdate(Long userId) {
        return userWalletRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> createWallet(requireUser(userId)));
    }

    @Transactional(readOnly = true)
    public BigDecimal currentBalanceOrZero(Long userId) {
        return userWalletRepository.findByUser_Id(userId)
                .map(UserWallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    public UserWalletTransaction createTransaction(
            UserWallet wallet,
            UserWalletTransactionType transactionType,
            BigDecimal amountDelta,
            WalletReferenceType referenceType,
            Long referenceId,
            String description
    ) {
        UserWalletTransaction transaction = new UserWalletTransaction();
        transaction.setWallet(wallet);
        transaction.setUser(wallet.getUser());
        transaction.setTransactionType(transactionType);
        transaction.setAmountDelta(amountDelta);
        transaction.setBalanceAfter(wallet.getBalance());
        transaction.setCurrency(wallet.getCurrency());
        transaction.setReferenceType(referenceType);
        transaction.setReferenceId(referenceId);
        transaction.setDescription(normalizeText(description));
        return userWalletTransactionRepository.save(transaction);
    }

    public UserWalletResponse toWalletResponse(UserWallet wallet) {
        return new UserWalletResponse(
                wallet.getUser().getId(),
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getStatus().name(),
                wallet.getUpdatedAt()
        );
    }

    public UserWalletTransactionResponse toTransactionResponse(UserWalletTransaction transaction) {
        return new UserWalletTransactionResponse(
                transaction.getId(),
                transaction.getTransactionType().name(),
                transaction.getAmountDelta(),
                transaction.getBalanceAfter(),
                transaction.getCurrency(),
                transaction.getReferenceType() == null ? null : transaction.getReferenceType().name(),
                transaction.getReferenceId(),
                transaction.getDescription(),
                transaction.getCreatedAt()
        );
    }

    public RechargeOrderResponse toRechargeOrderResponse(RechargeOrder order) {
        return new RechargeOrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getUser().getEmail(),
                order.getUser().getDisplayName(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus().name(),
                order.getRemark(),
                order.getReviewedBy() == null ? null : order.getReviewedBy().getId(),
                order.getReviewedAt(),
                order.getReviewRemark(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private UserWallet createWallet(AppUser user) {
        UserWallet wallet = new UserWallet();
        wallet.setUser(user);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setCurrency(normalizeCurrency(billingProperties.getCurrency()));
        wallet.setStatus(UserWalletStatus.ACTIVE);
        return userWalletRepository.saveAndFlush(wallet);
    }

    private AppUser requireUser(Long userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
    }

    private String normalizeCurrency(String currency) {
        return currency == null ? "CNY" : currency.trim().toUpperCase();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
