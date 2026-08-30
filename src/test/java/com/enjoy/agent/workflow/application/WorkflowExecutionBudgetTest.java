package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowExecutionBudgetTest {

    @Test
    void rejectsTheNextToolBeforeItCanCreateAnotherSideEffect() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxToolCalls(1);
        WorkflowExecutionBudget budget = new WorkflowExecutionBudget(properties);

        budget.beforeToolCall();

        assertThatThrownBy(budget::beforeToolCall)
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("WORKFLOW_TOOL_CALL_BUDGET_EXCEEDED"));
    }

    @Test
    void reservesPromptAndMaximumOutputBeforeStartingARealModelCall() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxTotalModelTokens(100);
        properties.setMaxPromptTokenUpperBoundPerCall(100);
        WorkflowExecutionBudget budget = new WorkflowExecutionBudget(properties);

        assertThatThrownBy(() -> budget.beforeModelCall(80, 30))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("WORKFLOW_MODEL_TOKEN_BUDGET_EXCEEDED"));
        assertThat(budget.modelCalls()).isZero();
    }

    @Test
    void missingUsageConsumesTheConservativePromptAndOutputReservation() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxTotalModelTokens(100);
        properties.setMaxPromptTokenUpperBoundPerCall(100);
        WorkflowExecutionBudget budget = new WorkflowExecutionBudget(properties);
        ModelGatewayChatCompletion noUsage = completion(null, null);

        budget.recordModelUsage(budget.beforeModelCall(40, 10), noUsage);
        budget.recordModelUsage(budget.beforeModelCall(40, 10), noUsage);

        assertThat(budget.promptTokens()).isEqualTo(80);
        assertThat(budget.completionTokens()).isEqualTo(20);
        assertThatThrownBy(() -> budget.beforeModelCall(1, 1))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("WORKFLOW_MODEL_TOKEN_BUDGET_EXCEEDED"));
    }

    @Test
    void partialUsageFallsBackOnlyForTheMissingComponent() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxTotalModelTokens(100);
        WorkflowExecutionBudget budget = new WorkflowExecutionBudget(properties);

        budget.recordModelUsage(budget.beforeModelCall(40, 30), completion(12, null));

        assertThat(budget.promptTokens()).isEqualTo(12);
        assertThat(budget.completionTokens()).isEqualTo(30);
    }

    private ModelGatewayChatCompletion completion(Integer prompt, Integer completion) {
        return new ModelGatewayChatCompletion(
                "ok", List.of(), CredentialProvider.OPENAI, "model", CredentialSource.USER,
                1L, 1L, prompt, completion,
                prompt == null || completion == null ? null : prompt + completion);
    }
}
