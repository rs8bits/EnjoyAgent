package com.enjoy.agent.billing.api;

import com.enjoy.agent.billing.api.response.UserWalletResponse;
import com.enjoy.agent.billing.api.response.UserWalletTransactionResponse;
import com.enjoy.agent.billing.application.WalletApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "钱包", description = "查询当前用户钱包与流水")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletApplicationService walletApplicationService;

    public WalletController(WalletApplicationService walletApplicationService) {
        this.walletApplicationService = walletApplicationService;
    }

    @Operation(summary = "当前钱包")
    @GetMapping
    public ApiResponse<UserWalletResponse> getWallet() {
        return ApiResponse.success(walletApplicationService.getCurrentWallet());
    }

    @Operation(summary = "当前用户钱包流水")
    @GetMapping("/transactions")
    public ApiResponse<List<UserWalletTransactionResponse>> listTransactions() {
        return ApiResponse.success(walletApplicationService.listCurrentUserTransactions());
    }
}
