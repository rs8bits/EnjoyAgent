package com.enjoy.agent.market.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "MarketAssetResponse", description = "市场资产返回对象")
public record MarketAssetResponse(
        @Schema(description = "资产 ID", example = "1")
        Long id,

        @Schema(description = "资产类型", example = "AGENT")
        String assetType,

        @Schema(description = "源对象 ID", example = "1001")
        Long sourceEntityId,

        @Schema(description = "资产名称", example = "产品助手模板")
        String name,

        @Schema(description = "摘要", example = "适合做产品知识问答的 Agent 模板")
        String summary,

        @Schema(description = "详细介绍")
        String description,

        @Schema(description = "状态", example = "APPROVED")
        String status,

        @Schema(description = "提交人用户 ID", example = "11")
        Long submitterUserId,

        @Schema(description = "提交人昵称", example = "Alice")
        String submitterDisplayName,

        @Schema(description = "审核人用户 ID", example = "2")
        Long reviewedByUserId,

        @Schema(description = "审核人昵称", example = "Admin")
        String reviewedByDisplayName,

        @Schema(description = "审核时间")
        Instant reviewedAt,

        @Schema(description = "审核备注", example = "允许上架")
        String reviewRemark,

        @Schema(description = "发布时间")
        Instant publishedAt,

        @Schema(description = "安装次数", example = "5")
        Integer installCount,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
