package com.enjoy.agent.billing.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(name = "RechargeOrderResponse", description = "充值单返回对象")
public record RechargeOrderResponse(
        @Schema(description = "充值单 ID", example = "1")
        Long id,

        @Schema(description = "用户 ID", example = "1")
        Long userId,

        @Schema(description = "用户邮箱", example = "alice@example.com")
        String userEmail,

        @Schema(description = "用户昵称", example = "Alice")
        String userDisplayName,

        @Schema(description = "充值金额", example = "50.000000")
        BigDecimal amount,

        @Schema(description = "币种", example = "CNY")
        String currency,

        @Schema(description = "状态", example = "PENDING")
        String status,

        @Schema(description = "用户备注", example = "线下转账已完成")
        String remark,

        @Schema(description = "审核人 ID", example = "2")
        Long reviewedBy,

        @Schema(description = "审核时间")
        Instant reviewedAt,

        @Schema(description = "审核备注", example = "已核对到账")
        String reviewRemark,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
