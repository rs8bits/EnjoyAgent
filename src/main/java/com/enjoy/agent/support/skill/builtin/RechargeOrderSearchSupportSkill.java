package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.billing.infrastructure.persistence.RechargeOrderRepository;
import com.enjoy.agent.billing.infrastructure.persistence.RechargeOrderSummaryProjection;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRequest;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 查询当前用户的人工审核充值申请，不暴露邮箱、审核人 ID 等无关信息。 */
@Component
public class RechargeOrderSearchSupportSkill implements SupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "recharge_order.search", "1.0.0", "查询充值申请",
            "查询当前用户的人工待审核充值申请及处理状态",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\"},\"hasMore\":{\"type\":\"boolean\"},\"mode\":{\"type\":\"string\"}}}",
            SupportSkillRisk.READ, "CURRENT_USER", false, 2_000
    );

    private final RechargeOrderRepository repository;
    private final SupportSkillPayloadWriter payloadWriter;

    public RechargeOrderSearchSupportSkill(
            RechargeOrderRepository repository,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.repository = repository;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() {
        return MANIFEST;
    }

    @Override
    @Transactional(readOnly = true)
    public SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request) {
        List<Map<String, Object>> items;
        boolean hasMore = false;
        if (request.resourceId() != null) {
            items = repository.findSupportSummaryByIdAndUserId(
                            request.resourceId(), context.userId())
                    .map(this::project)
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            Slice<RechargeOrderSummaryProjection> page = repository.findSupportSummariesByUserId(
                    context.userId(),
                    PageRequest.of(0, context.pageSize())
            );
            items = page.getContent().stream().map(this::project).toList();
            hasMore = page.hasNext();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "MANUAL_REVIEW");
        payload.put("items", items);
        payload.put("hasMore", hasMore);
        return new SupportSkillResult(
                payloadWriter.write(payload),
                "已查询到 " + items.size() + " 条人工审核充值申请"
        );
    }

    private Map<String, Object> project(RechargeOrderSummaryProjection order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", order.id());
        item.put("amount", order.amount().toPlainString());
        item.put("currency", order.currency());
        item.put("status", order.status().name());
        item.put("remark", payloadWriter.safeText(order.remark(), 120));
        item.put("reviewedAt", order.reviewedAt());
        item.put("reviewRemark", payloadWriter.safeText(order.reviewRemark(), 120));
        item.put("createdAt", order.createdAt());
        item.put("updatedAt", order.updatedAt());
        return item;
    }
}
