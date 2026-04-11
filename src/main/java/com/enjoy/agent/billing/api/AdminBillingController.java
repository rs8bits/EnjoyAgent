package com.enjoy.agent.billing.api;

import com.enjoy.agent.billing.api.request.AdjustUserWalletRequest;
import com.enjoy.agent.billing.api.request.ReviewRechargeOrderRequest;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.api.response.UserWalletResponse;
import com.enjoy.agent.billing.application.AdminBillingApplicationService;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端-账务", description = "管理员审核充值单和管理用户钱包")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/billing")
public class AdminBillingController {

    private final AdminBillingApplicationService adminBillingApplicationService;

    public AdminBillingController(AdminBillingApplicationService adminBillingApplicationService) {
        this.adminBillingApplicationService = adminBillingApplicationService;
    }

    @Operation(summary = "充值单列表")
    @GetMapping("/recharge-orders")
    public ApiResponse<List<RechargeOrderResponse>> listRechargeOrders(
            @RequestParam(required = false) RechargeOrderStatus status
    ) {
        return ApiResponse.success(adminBillingApplicationService.listRechargeOrders(status));
    }

    @Operation(summary = "审核通过充值单")
    @PostMapping("/recharge-orders/{id}/approve")
    public ApiResponse<RechargeOrderResponse> approveRechargeOrder(
            @PathVariable Long id,
            @Valid @RequestBody ReviewRechargeOrderRequest request
    ) {
        return ApiResponse.success(adminBillingApplicationService.approveRechargeOrder(id, request), "Recharge order approved");
    }

    @Operation(summary = "驳回充值单")
    @PostMapping("/recharge-orders/{id}/reject")
    public ApiResponse<RechargeOrderResponse> rejectRechargeOrder(
            @PathVariable Long id,
            @Valid @RequestBody ReviewRechargeOrderRequest request
    ) {
        return ApiResponse.success(adminBillingApplicationService.rejectRechargeOrder(id, request), "Recharge order rejected");
    }

    @Operation(summary = "查询用户钱包")
    @GetMapping("/users/{userId}/wallet")
    public ApiResponse<UserWalletResponse> getUserWallet(@PathVariable Long userId) {
        return ApiResponse.success(adminBillingApplicationService.getUserWallet(userId));
    }

    @Operation(summary = "调整用户钱包")
    @PostMapping("/users/{userId}/wallet/adjust")
    public ApiResponse<UserWalletResponse> adjustUserWallet(
            @PathVariable Long userId,
            @Valid @RequestBody AdjustUserWalletRequest request
    ) {
        return ApiResponse.success(adminBillingApplicationService.adjustUserWallet(userId, request), "Wallet adjusted");
    }
}
