package com.enjoy.agent.billing.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "ReviewRechargeOrderRequest", description = "审核充值单请求")
public record ReviewRechargeOrderRequest(
        @Schema(description = "审核备注", example = "已核对到账")
        @Size(max = 512)
        String reviewRemark
) {
}
