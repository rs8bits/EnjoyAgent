package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpModelTokenBudgetTest {

    @Test
    void nullUsageConsumesBothConservativeReservationComponentsBeforeNextCall() {
        McpModelTokenBudget budget = new McpModelTokenBudget(100);
        budget.record(budget.beforeCall(40, 10), completion(null, null));
        budget.record(budget.beforeCall(40, 10), completion(null, null));

        assertThat(budget.committedTokens()).isEqualTo(100);
        assertThatThrownBy(() -> budget.beforeCall(1, 1))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("MCP_MODEL_TOKEN_BUDGET_EXCEEDED"));
    }

    @Test
    void partialUsageFallsBackOnlyForMissingCompletionTokens() {
        McpModelTokenBudget budget = new McpModelTokenBudget(100);

        budget.record(budget.beforeCall(40, 30), completion(12, null));

        assertThat(budget.committedTokens()).isEqualTo(42);
    }

    private ModelGatewayChatCompletion completion(Integer prompt, Integer output) {
        return new ModelGatewayChatCompletion(
                "ok", List.of(), CredentialProvider.OPENAI, "model", CredentialSource.USER,
                1L, 1L, prompt, output,
                prompt == null || output == null ? null : prompt + output);
    }
}
