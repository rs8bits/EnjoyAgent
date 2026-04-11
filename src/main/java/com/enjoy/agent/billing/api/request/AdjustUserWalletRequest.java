package com.enjoy.agent.billing.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(name = "AdjustUserWalletRequest", description = "管理员调整用户钱包请求")
public record AdjustUserWalletRequest(
        @Schema(description = "调整金额，正数加款，负数扣款", example = "-5.50")
        @NotNull
        BigDecimal amountDelta,

        @Schema(description = "调整说明", example = "客服补偿")
        @Size(max = 512)
        String description
) {
}
