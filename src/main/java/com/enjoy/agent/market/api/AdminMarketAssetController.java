package com.enjoy.agent.market.api;

import com.enjoy.agent.market.api.request.ReviewMarketAssetRequest;
import com.enjoy.agent.market.api.response.MarketAssetResponse;
import com.enjoy.agent.market.application.MarketAssetApplicationService;
import com.enjoy.agent.market.domain.enums.MarketAssetStatus;
import com.enjoy.agent.market.domain.enums.MarketAssetType;
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

@Tag(name = "管理端-共享市场", description = "共享市场审核与上下架管理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/market/assets")
public class AdminMarketAssetController {

    private final MarketAssetApplicationService marketAssetApplicationService;

    public AdminMarketAssetController(MarketAssetApplicationService marketAssetApplicationService) {
        this.marketAssetApplicationService = marketAssetApplicationService;
    }

    @Operation(summary = "管理端市场资产列表")
    @GetMapping
    public ApiResponse<List<MarketAssetResponse>> listAssets(
            @RequestParam(required = false) MarketAssetType assetType,
            @RequestParam(required = false) MarketAssetStatus status
    ) {
        return ApiResponse.success(marketAssetApplicationService.listAdminAssets(assetType, status));
    }

    @Operation(summary = "审核通过市场资产")
    @PostMapping("/{id}/approve")
    public ApiResponse<MarketAssetResponse> approveAsset(
            @PathVariable Long id,
            @Valid @RequestBody ReviewMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.approveAsset(id, request), "Market asset approved");
    }

    @Operation(summary = "驳回市场资产")
    @PostMapping("/{id}/reject")
    public ApiResponse<MarketAssetResponse> rejectAsset(
            @PathVariable Long id,
            @Valid @RequestBody ReviewMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.rejectAsset(id, request), "Market asset rejected");
    }

    @Operation(summary = "下架市场资产")
    @PostMapping("/{id}/offline")
    public ApiResponse<MarketAssetResponse> offlineAsset(
            @PathVariable Long id,
            @Valid @RequestBody ReviewMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.offlineAsset(id, request), "Market asset offline");
    }
}
