package com.enjoy.agent.billing.api;

import com.enjoy.agent.billing.api.request.CreateRechargeOrderRequest;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.application.WalletApplicationService;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "充值单", description = "用户创建和查询充值单")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/recharge-orders")
public class RechargeOrderController {

    private final WalletApplicationService walletApplicationService;

    public RechargeOrderController(WalletApplicationService walletApplicationService) {
        this.walletApplicationService = walletApplicationService;
    }

    @Operation(summary = "创建充值单")
    @PostMapping
    public ApiResponse<RechargeOrderResponse> createRechargeOrder(
            @Valid @RequestBody CreateRechargeOrderRequest request
    ) {
        return ApiResponse.success(walletApplicationService.createRechargeOrder(request), "Recharge order created");
    }

    @Operation(summary = "当前用户充值单列表")
    @GetMapping
    public ApiResponse<List<RechargeOrderResponse>> listRechargeOrders() {
        return ApiResponse.success(walletApplicationService.listCurrentUserRechargeOrders());
    }

    @Operation(summary = "当前用户充值单详情")
    @GetMapping("/{id}")
    public ApiResponse<RechargeOrderResponse> getRechargeOrder(@PathVariable Long id) {
        return ApiResponse.success(walletApplicationService.getCurrentUserRechargeOrder(id));
    }
}
