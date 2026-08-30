package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowConditionEvaluatorTest {

    private final WorkflowConditionEvaluator evaluator =
            new WorkflowConditionEvaluator(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void resolvesNestedValuesAndEvaluatesNumericComparison() {
        String resolved = evaluator.resolveTemplates(
                "{{#counter.value#}} >= 3",
                Map.of("node_outputs", Map.of("counter", Map.of("value", 4)))
        );

        assertThat(evaluator.evaluateInline(resolved)).isTrue();
    }

    @Test
    void preservesUnknownVariablesInsteadOfReadingUnscopedValues() {
        assertThat(evaluator.resolveTemplates(
                "{{#inactive.value#}} == yes", Map.of("node_outputs", Map.of())))
                .isEqualTo("{{#inactive.value#}} == yes");
    }

    @Test
    void rejectsUnknownOperatorsInsteadOfFallingBackToTruthy() {
        assertThatThrownBy(() -> evaluator.evaluate("value", "spel", "value"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("WORKFLOW_CONDITION_INVALID"));
    }
}
