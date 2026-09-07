package com.enjoy.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 数据库约束属于安全设计的一部分，测试防止后续重构意外删除它们。 */
class SupportSchemaContractTest {

    @Test
    void migration_shouldEnforceTenantUserSingletonAndSingleRunningTurn() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V32__create_support_customer_service_tables.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("UNIQUE (tenant_id, user_id)")
                .contains("uk_support_turn_one_running_per_conversation")
                .contains("WHERE status = 'RUNNING'")
                .contains("owner_token              UUID         NOT NULL")
                .contains("version                  BIGINT       NOT NULL DEFAULT 0")
                .contains("UNIQUE (conversation_id, sequence)");
    }
}
