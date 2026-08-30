package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.chat.application.ChatApplicationService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BillingDataRetentionContractTest {

    @Test
    void chatSessionDeletion_shouldHaveNoBillingUsageEventRepositoryDependency() {
        assertThat(Arrays.stream(ChatApplicationService.class.getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(BillingUsageEventRepository.class);
        assertThat(Arrays.stream(BillingUsageEventRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("deleteAllBySessionId");
    }

    @Test
    void migration_shouldDetachBillingSnapshotAndAddDebitIdempotencyConstraint() throws IOException {
        String migration = new ClassPathResource(
                "db/migration/V26__preserve_billing_usage_facts.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("DROP CONSTRAINT IF EXISTS fk_billing_usage_event_model_call_log")
                .contains("CREATE UNIQUE INDEX uk_user_wallet_transaction_model_usage_call")
                .contains("transaction_type = 'MODEL_USAGE_DEBIT'")
                .contains("reference_type = 'MODEL_CALL_LOG'")
                .contains("reference_id IS NOT NULL");
    }

    @Test
    void reliabilityMigration_shouldDefendRechargeAndOutboxIdempotency() throws IOException {
        String migration = new ClassPathResource(
                "db/migration/V29__add_billing_and_knowledge_reliability.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("uk_user_wallet_transaction_recharge_order")
                .contains("transaction_type = 'RECHARGE_APPROVED'")
                .contains("uk_billing_usage_outbox_event")
                .contains("usage_estimated BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("ck_user_wallet_reserved_within_balance")
                .contains("reserved_amount <= balance")
                .contains("uk_knowledge_document_tenant_base_content");
    }
}
