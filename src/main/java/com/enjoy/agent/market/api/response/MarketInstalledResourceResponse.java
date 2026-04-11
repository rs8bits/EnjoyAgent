package com.enjoy.agent.market.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MarketInstalledResourceResponse", description = "安装过程中额外创建的资源")
public record MarketInstalledResourceResponse(
        @Schema(description = "资源类型", example = "KNOWLEDGE_BASE")
        String resourceType,

        @Schema(description = "资源 ID", example = "2001")
        Long id,

        @Schema(description = "资源名称", example = "产品助手知识库")
        String name
) {
}
