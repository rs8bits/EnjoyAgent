package com.enjoy.agent.billing.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(name = "UserWalletTransactionResponse", description = "用户钱包流水返回对象")
public record UserWalletTransactionResponse(
        @Schema(description = "流水 ID", example = "1")
        Long id,

        @Schema(description = "流水类型", example = "MODEL_USAGE_DEBIT")
        String transactionType,

        @Schema(description = "变动金额", example = "-0.005300")
        BigDecimal amountDelta,

        @Schema(description = "变动后余额", example = "88.494700")
        BigDecimal balanceAfter,

        @Schema(description = "币种", example = "CNY")
        String currency,

        @Schema(description = "引用对象类型", example = "MODEL_CALL_LOG")
        String referenceType,

        @Schema(description = "引用对象 ID", example = "9527")
        Long referenceId,

        @Schema(description = "说明", example = "官方模型调用扣费")
        String description,

        @Schema(description = "创建时间")
        Instant createdAt
) {
}
