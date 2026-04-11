package com.enjoy.agent.billing.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(name = "CreateRechargeOrderRequest", description = "创建充值单请求")
public record CreateRechargeOrderRequest(
        @Schema(description = "充值金额", example = "50")
        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount,

        @Schema(description = "充值备注", example = "线下转账已完成")
        @Size(max = 512)
        String remark
) {
}
