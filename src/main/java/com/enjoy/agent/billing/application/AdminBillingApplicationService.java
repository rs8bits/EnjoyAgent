package com.enjoy.agent.billing.application;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.billing.api.request.AdjustUserWalletRequest;
import com.enjoy.agent.billing.api.request.ReviewRechargeOrderRequest;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.api.response.UserWalletResponse;
import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletStatus;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.billing.infrastructure.persistence.RechargeOrderRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理员侧账务能力。
 */
@Service
public class AdminBillingApplicationService {

    private final AppUserRepository appUserRepository;
    private final RechargeOrderRepository rechargeOrderRepository;
    private final WalletSupportService walletSupportService;
    private final Clock clock;

    public AdminBillingApplicationService(
            AppUserRepository appUserRepository,
            RechargeOrderRepository rechargeOrderRepository,
            WalletSupportService walletSupportService,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.walletSupportService = walletSupportService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RechargeOrderResponse> listRechargeOrders(RechargeOrderStatus status) {
        requireAdmin();
        List<RechargeOrder> orders = status == null
                ? rechargeOrderRepository.findAllByOrderByIdDesc()
                : rechargeOrderRepository.findAllByStatusOrderByIdDesc(status);
        return orders.stream()
                .map(walletSupportService::toRechargeOrderResponse)
                .toList();
    }

    @Transactional
    public RechargeOrderResponse approveRechargeOrder(Long id, ReviewRechargeOrderRequest request) {
        AppUser reviewer = requireAdminUser();
        RechargeOrder order = requirePendingRechargeOrder(id);
        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(order.getUser().getId());
        requireWalletActive(wallet);

        wallet.setBalance(wallet.getBalance().add(order.getAmount()));
        walletSupportService.createTransaction(
                wallet,
                UserWalletTransactionType.RECHARGE_APPROVED,
                order.getAmount(),
                WalletReferenceType.RECHARGE_ORDER,
                order.getId(),
                "充值单审核通过入账"
        );

        order.setStatus(RechargeOrderStatus.APPROVED);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(Instant.now(clock));
        order.setReviewRemark(normalizeText(request.reviewRemark()));
        return walletSupportService.toRechargeOrderResponse(rechargeOrderRepository.save(order));
    }

    @Transactional
    public RechargeOrderResponse rejectRechargeOrder(Long id, ReviewRechargeOrderRequest request) {
        AppUser reviewer = requireAdminUser();
        RechargeOrder order = requirePendingRechargeOrder(id);
        order.setStatus(RechargeOrderStatus.REJECTED);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(Instant.now(clock));
        order.setReviewRemark(normalizeText(request.reviewRemark()));
        return walletSupportService.toRechargeOrderResponse(rechargeOrderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public UserWalletResponse getUserWallet(Long userId) {
        requireAdmin();
        return walletSupportService.toWalletResponse(walletSupportService.loadOrCreateWallet(userId));
    }

    @Transactional
    public UserWalletResponse adjustUserWallet(Long userId, AdjustUserWalletRequest request) {
        requireAdmin();
        BigDecimal amountDelta = request.amountDelta();
        if (amountDelta.signum() == 0) {
            throw new ApiException("WALLET_ADJUST_AMOUNT_INVALID", "Wallet adjust amount must not be zero", HttpStatus.BAD_REQUEST);
        }

        UserWallet wallet = walletSupportService.loadOrCreateWalletForUpdate(userId);
        requireWalletActive(wallet);
        wallet.setBalance(wallet.getBalance().add(amountDelta));
        walletSupportService.createTransaction(
                wallet,
                UserWalletTransactionType.MANUAL_ADJUST,
                amountDelta,
                WalletReferenceType.ADMIN_ADJUSTMENT,
                null,
                normalizeText(request.description()) == null ? "管理员手工调账" : normalizeText(request.description())
        );
        return walletSupportService.toWalletResponse(wallet);
    }

    private RechargeOrder requirePendingRechargeOrder(Long id) {
        RechargeOrder order = rechargeOrderRepository.findById(id)
                .orElseThrow(() -> new ApiException("RECHARGE_ORDER_NOT_FOUND", "Recharge order not found", HttpStatus.NOT_FOUND));
        if (order.getStatus() != RechargeOrderStatus.PENDING) {
            throw new ApiException("RECHARGE_ORDER_STATUS_INVALID", "Recharge order is not pending", HttpStatus.BAD_REQUEST);
        }
        return order;
    }

    private void requireWalletActive(UserWallet wallet) {
        if (wallet.getStatus() != UserWalletStatus.ACTIVE) {
            throw new ApiException("WALLET_DISABLED", "Wallet is disabled", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireAdmin() {
        requireAdminUser();
    }

    private AppUser requireAdminUser() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        if (currentUser.systemRole() != SystemRole.ADMIN) {
            throw new ApiException("FORBIDDEN", "Admin permission required", HttpStatus.FORBIDDEN);
        }
        return appUserRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
