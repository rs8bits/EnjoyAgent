package com.enjoy.agent.billing.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(name = "UserWalletResponse", description = "用户钱包返回对象")
public record UserWalletResponse(
        @Schema(description = "用户 ID", example = "1")
        Long userId,

        @Schema(description = "当前余额", example = "88.500000")
        BigDecimal balance,

        @Schema(description = "币种", example = "CNY")
        String currency,

        @Schema(description = "钱包状态", example = "ACTIVE")
        String status,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
