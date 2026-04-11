package com.enjoy.agent.market.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "ReviewMarketAssetRequest", description = "审核市场资产请求")
public record ReviewMarketAssetRequest(
        @Schema(description = "审核备注", example = "结构完整，允许上架")
        @Size(max = 512)
        String reviewRemark
) {
}
