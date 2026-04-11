package com.enjoy.agent.market.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "InstallMarketAssetRequest", description = "安装共享市场资产请求")
public record InstallMarketAssetRequest(
        @Schema(description = "安装后名称；不传则默认使用市场资产名称", example = "产品助手模板-我的副本")
        @Size(max = 128)
        String name,

        @Schema(description = "安装 Agent 时绑定的用户聊天模型配置 ID", example = "101")
        Long targetModelConfigId,

        @Schema(description = "安装 Agent 时绑定的官方聊天模型配置 ID", example = "11")
        Long targetOfficialModelConfigId,

        @Schema(description = "安装 Agent 时绑定的知识库 ID", example = "201")
        Long targetKnowledgeBaseId,

        @Schema(description = "安装 Agent 时绑定的重排模型配置 ID", example = "301")
        Long targetRerankModelConfigId,

        @Schema(description = "安装知识库时绑定的 embedding 模型配置 ID", example = "401")
        Long targetEmbeddingModelConfigId,

        @Schema(description = "安装 MCP Server 时绑定的凭证 ID", example = "501")
        Long targetCredentialId,

        @Schema(description = "安装后是否启用", example = "true")
        Boolean enabled
) {
}
