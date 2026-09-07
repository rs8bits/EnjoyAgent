package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** 不读取申请人邮箱和审核人实体的人工充值申请安全投影。 */
public record RechargeOrderSummaryProjection(
        Long id,
        BigDecimal amount,
        String currency,
        RechargeOrderStatus status,
        String remark,
        Instant reviewedAt,
        String reviewRemark,
        Instant createdAt,
        Instant updatedAt
) {
}
