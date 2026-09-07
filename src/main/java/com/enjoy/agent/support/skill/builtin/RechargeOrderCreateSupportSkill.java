package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.billing.api.request.CreateRechargeOrderRequest;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.application.BillingProperties;
import com.enjoy.agent.billing.application.WalletApplicationService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.ConfirmableSupportSkill;
import com.enjoy.agent.support.skill.SupportActionPreview;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 创建人工审核充值申请；确认后只产生 PENDING 申请，不直接修改钱包余额。 */
@Component
public class RechargeOrderCreateSupportSkill implements ConfirmableSupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "recharge_order.create", "1.0.0", "创建充值申请",
            "为当前用户创建人工审核充值申请；不会立即增加余额，执行前必须确认金额",
            "{\"type\":\"object\",\"properties\":{"
                    + "\"amount\":{\"type\":\"number\",\"minimum\":0.01},"
                    + "\"remark\":{\"type\":\"string\",\"maxLength\":512}},"
                    + "\"required\":[\"amount\"],\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"integer\"},"
                    + "\"status\":{\"type\":\"string\"},\"amount\":{\"type\":\"string\"}}}",
            SupportSkillRisk.FINANCIAL, "CURRENT_USER", true, 1_000
    );

    private final WalletApplicationService walletApplicationService;
    private final BillingProperties billingProperties;
    private final SupportSkillPayloadWriter payloadWriter;

    public RechargeOrderCreateSupportSkill(
            WalletApplicationService walletApplicationService,
            BillingProperties billingProperties,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.walletApplicationService = walletApplicationService;
        this.billingProperties = billingProperties;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() { return MANIFEST; }

    @Override
    public SupportActionPreview preview(
            SupportSkillExecutionContext context,
            Map<String, Object> requestedArguments
    ) {
        BigDecimal amount = requireAmount(requestedArguments);
        String remark = normalizeRemark(requestedArguments == null ? null : requestedArguments.get("remark"));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("amount", amount.toPlainString());
        if (remark != null) {
            normalized.put("remark", remark);
        }
        String currency = normalizeCurrency(billingProperties.getCurrency());
        String remarkText = remark == null ? "无备注" : "备注：" + payloadWriter.safeText(remark, 120);
        return new SupportActionPreview(
                normalized,
                "确认提交充值申请",
                "将提交 " + amount.toPlainString() + " " + currency + " 的人工审核充值申请（"
                        + remarkText + "）。确认后只进入 PENDING 审核，不代表余额已经到账。",
                null
        );
    }

    @Override
    public SupportSkillResult executeConfirmed(
            SupportSkillExecutionContext context,
            Map<String, Object> normalizedArguments,
            SupportActionPreview originalPreview
    ) {
        BigDecimal amount = requireAmount(normalizedArguments);
        String remark = normalizeRemark(normalizedArguments == null ? null : normalizedArguments.get("remark"));
        RechargeOrderResponse order = walletApplicationService.createRechargeOrder(
                new CreateRechargeOrderRequest(amount, remark));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("amount", order.amount());
        payload.put("currency", order.currency());
        payload.put("status", order.status());
        return new SupportSkillResult(
                payloadWriter.write(payload),
                "已创建充值申请 #" + order.id() + "，状态为 " + order.status()
                        + "；需要管理员人工审核后才会到账"
        );
    }

    private BigDecimal requireAmount(Map<String, Object> arguments) {
        try {
            Object value = arguments == null ? null : arguments.get("amount");
            BigDecimal amount = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
            int integerDigits = Math.max(0, amount.precision() - amount.scale());
            if (amount.compareTo(new BigDecimal("0.01")) < 0
                    || amount.scale() > 6
                    || integerDigits > 12) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (RuntimeException ex) {
            throw new ApiException(
                    "SUPPORT_RECHARGE_AMOUNT_INVALID",
                    "充值金额必须大于等于 0.01，最多 12 位整数和 6 位小数",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private String normalizeRemark(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String remark = String.valueOf(value).trim();
        if (remark.codePointCount(0, remark.length()) > 512) {
            throw new ApiException(
                    "SUPPORT_RECHARGE_REMARK_TOO_LONG", "充值备注不能超过 512 个字符", HttpStatus.BAD_REQUEST);
        }
        return payloadWriter.safeText(remark, 512);
    }

    private String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
    }
}
