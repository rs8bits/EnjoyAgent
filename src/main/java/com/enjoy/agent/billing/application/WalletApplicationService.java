package com.enjoy.agent.billing.application;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.billing.api.request.CreateRechargeOrderRequest;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.api.response.UserWalletResponse;
import com.enjoy.agent.billing.api.response.UserWalletTransactionResponse;
import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import com.enjoy.agent.billing.infrastructure.persistence.RechargeOrderRepository;
import com.enjoy.agent.billing.infrastructure.persistence.UserWalletTransactionRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户侧钱包与充值单能力。
 */
@Service
public class WalletApplicationService {

    private final AppUserRepository appUserRepository;
    private final RechargeOrderRepository rechargeOrderRepository;
    private final UserWalletTransactionRepository userWalletTransactionRepository;
    private final WalletSupportService walletSupportService;
    private final BillingProperties billingProperties;

    public WalletApplicationService(
            AppUserRepository appUserRepository,
            RechargeOrderRepository rechargeOrderRepository,
            UserWalletTransactionRepository userWalletTransactionRepository,
            WalletSupportService walletSupportService,
            BillingProperties billingProperties
    ) {
        this.appUserRepository = appUserRepository;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.userWalletTransactionRepository = userWalletTransactionRepository;
        this.walletSupportService = walletSupportService;
        this.billingProperties = billingProperties;
    }

    @Transactional
    public UserWalletResponse getCurrentWallet() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return walletSupportService.toWalletResponse(walletSupportService.loadOrCreateWallet(currentUser.userId()));
    }

    @Transactional(readOnly = true)
    public List<UserWalletTransactionResponse> listCurrentUserTransactions() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return userWalletTransactionRepository.findTop100ByUser_IdOrderByIdDesc(currentUser.userId()).stream()
                .map(walletSupportService::toTransactionResponse)
                .toList();
    }

    @Transactional
    public RechargeOrderResponse createRechargeOrder(CreateRechargeOrderRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        AppUser user = appUserRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));

        RechargeOrder order = new RechargeOrder();
        order.setUser(user);
        order.setAmount(request.amount());
        order.setCurrency(normalizeCurrency(billingProperties.getCurrency()));
        order.setStatus(RechargeOrderStatus.PENDING);
        order.setRemark(normalizeText(request.remark()));
        return walletSupportService.toRechargeOrderResponse(rechargeOrderRepository.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public List<RechargeOrderResponse> listCurrentUserRechargeOrders() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return rechargeOrderRepository.findAllByUser_IdOrderByIdDesc(currentUser.userId()).stream()
                .map(walletSupportService::toRechargeOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RechargeOrderResponse getCurrentUserRechargeOrder(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        RechargeOrder order = rechargeOrderRepository.findByIdAndUser_Id(id, currentUser.userId())
                .orElseThrow(() -> new ApiException("RECHARGE_ORDER_NOT_FOUND", "Recharge order not found", HttpStatus.NOT_FOUND));
        return walletSupportService.toRechargeOrderResponse(order);
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
