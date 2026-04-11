package com.enjoy.agent.market.api;

import com.enjoy.agent.market.api.request.InstallMarketAssetRequest;
import com.enjoy.agent.market.api.request.SubmitMarketAssetRequest;
import com.enjoy.agent.market.api.response.MarketAssetInstallResponse;
import com.enjoy.agent.market.api.response.MarketAssetResponse;
import com.enjoy.agent.market.application.MarketAssetApplicationService;
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

@Tag(name = "共享市场", description = "提交、浏览和安装共享市场资产")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/market")
public class MarketAssetController {

    private final MarketAssetApplicationService marketAssetApplicationService;

    public MarketAssetController(MarketAssetApplicationService marketAssetApplicationService) {
        this.marketAssetApplicationService = marketAssetApplicationService;
    }

    @Operation(summary = "提交 Agent 到共享市场")
    @PostMapping("/assets/agents/{agentId}/submit")
    public ApiResponse<MarketAssetResponse> submitAgentAsset(
            @PathVariable Long agentId,
            @Valid @RequestBody SubmitMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.submitAgentAsset(agentId, request), "Market asset submitted");
    }

    @Operation(summary = "提交知识库到共享市场")
    @PostMapping("/assets/knowledge-bases/{knowledgeBaseId}/submit")
    public ApiResponse<MarketAssetResponse> submitKnowledgeBaseAsset(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody SubmitMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.submitKnowledgeBaseAsset(knowledgeBaseId, request), "Market asset submitted");
    }

    @Operation(summary = "提交 MCP Server 到共享市场")
    @PostMapping("/assets/mcp-servers/{serverId}/submit")
    public ApiResponse<MarketAssetResponse> submitMcpServerAsset(
            @PathVariable Long serverId,
            @Valid @RequestBody SubmitMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.submitMcpServerAsset(serverId, request), "Market asset submitted");
    }

    @Operation(summary = "当前用户提交记录")
    @GetMapping("/submissions")
    public ApiResponse<List<MarketAssetResponse>> listCurrentUserSubmissions() {
        return ApiResponse.success(marketAssetApplicationService.listCurrentUserSubmissions());
    }

    @Operation(summary = "已上架市场资产列表")
    @GetMapping("/assets")
    public ApiResponse<List<MarketAssetResponse>> listPublishedAssets(
            @RequestParam(required = false) MarketAssetType assetType
    ) {
        return ApiResponse.success(marketAssetApplicationService.listPublishedAssets(assetType));
    }

    @Operation(summary = "市场资产详情")
    @GetMapping("/assets/{id}")
    public ApiResponse<MarketAssetResponse> getAsset(@PathVariable Long id) {
        return ApiResponse.success(marketAssetApplicationService.getAsset(id));
    }

    @Operation(summary = "安装市场资产")
    @PostMapping("/assets/{id}/install")
    public ApiResponse<MarketAssetInstallResponse> installAsset(
            @PathVariable Long id,
            @Valid @RequestBody InstallMarketAssetRequest request
    ) {
        return ApiResponse.success(marketAssetApplicationService.installAsset(id, request), "Market asset installed");
    }
}
