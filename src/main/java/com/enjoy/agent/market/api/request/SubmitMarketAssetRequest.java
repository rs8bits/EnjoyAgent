package com.enjoy.agent.market.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "SubmitMarketAssetRequest", description = "提交共享市场资产请求")
public record SubmitMarketAssetRequest(
        @Schema(description = "摘要说明", example = "适合做企业知识问答的产品助手模板")
        @Size(max = 512)
        String summary,

        @Schema(description = "详细介绍", example = "包含 system prompt、上下文窗口和记忆策略，安装后请自行绑定知识库和工具。")
        @Size(max = 5000)
        String description
) {
}
