package com.enjoy.agent.market.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "MarketAssetInstallResponse", description = "市场资产安装结果")
public record MarketAssetInstallResponse(
        @Schema(description = "市场资产 ID", example = "1")
        Long marketAssetId,

        @Schema(description = "资产类型", example = "AGENT")
        String assetType,

        @Schema(description = "安装后的对象 ID", example = "10001")
        Long installedEntityId,

        @Schema(description = "安装后的对象名称", example = "产品助手模板-我的副本")
        String installedName,

        @Schema(description = "安装时额外生成的资源")
        List<MarketInstalledResourceResponse> relatedResources,

        @Schema(description = "是否还需要用户补充配置")
        boolean setupRequired,

        @Schema(description = "后续还需要处理的配置事项")
        List<String> setupItems
) {
}
