package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.billing.domain.entity.UserWallet;
import com.enjoy.agent.billing.domain.entity.UserWalletTransaction;
import com.enjoy.agent.billing.infrastructure.persistence.UserWalletRepository;
import com.enjoy.agent.billing.infrastructure.persistence.UserWalletTransactionRepository;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRequest;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 查询当前用户钱包余额和最近流水，不在查询过程中隐式创建钱包。 */
@Component
public class WalletSummarySupportSkill implements SupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "wallet.get_summary", "1.0.0", "查询钱包",
            "查询当前用户的钱包总额、冻结额、可用额与少量最近流水",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"wallet\":{\"type\":[\"object\",\"null\"]},\"recentTransactions\":{\"type\":\"array\"}}}",
            SupportSkillRisk.READ, "CURRENT_USER", false, 2_400
    );

    private final UserWalletRepository walletRepository;
    private final UserWalletTransactionRepository transactionRepository;
    private final SupportSkillPayloadWriter payloadWriter;

    public WalletSummarySupportSkill(
            UserWalletRepository walletRepository,
            UserWalletTransactionRepository transactionRepository,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() {
        return MANIFEST;
    }

    @Override
    @Transactional(readOnly = true)
    public SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request) {
        UserWallet wallet = walletRepository.findByUser_Id(context.userId()).orElse(null);
        List<Map<String, Object>> transactions = transactionRepository.findAllByUser_Id(
                        context.userId(),
                        PageRequest.of(0, Math.min(context.pageSize(), 5), Sort.by(Sort.Direction.DESC, "id"))
                ).getContent().stream()
                .map(this::projectTransaction)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("wallet", wallet == null ? null : projectWallet(wallet));
        payload.put("recentTransactions", transactions);
        String summary = wallet == null
                ? "当前用户尚无钱包记录"
                : "已查询钱包余额和 " + transactions.size() + " 条最近流水";
        return new SupportSkillResult(payloadWriter.write(payload), summary);
    }

    private Map<String, Object> projectWallet(UserWallet wallet) {
        BigDecimal balance = wallet.getBalance();
        BigDecimal reserved = wallet.getReservedAmount();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("balance", balance.toPlainString());
        item.put("reservedAmount", reserved.toPlainString());
        item.put("availableAmount", balance.subtract(reserved).max(BigDecimal.ZERO).toPlainString());
        item.put("currency", wallet.getCurrency());
        item.put("status", wallet.getStatus().name());
        item.put("updatedAt", wallet.getUpdatedAt());
        return item;
    }

    private Map<String, Object> projectTransaction(UserWalletTransaction transaction) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", transaction.getId());
        item.put("type", transaction.getTransactionType().name());
        item.put("amountDelta", transaction.getAmountDelta().toPlainString());
        item.put("balanceAfter", transaction.getBalanceAfter().toPlainString());
        item.put("currency", transaction.getCurrency());
        item.put("description", payloadWriter.safeText(transaction.getDescription(), 120));
        item.put("createdAt", transaction.getCreatedAt());
        return item;
    }
}
