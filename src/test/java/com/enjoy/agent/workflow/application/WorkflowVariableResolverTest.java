package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowVariableResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesCanonicalDifyAndShorthandSelectorsThroughNestedMaps() {
        Map<String, Object> context = Map.of(
                "node_outputs", Map.of(
                        "fetch", Map.of("payload", Map.of("score", 7))),
                "user_input", "question"
        );

        String resolved = WorkflowVariableResolver.resolveTextTemplate(
                "A={{node_outputs.fetch.payload.score}}; "
                        + "B={{#fetch.payload.score#}}; C={{fetch.payload.score}}; U={{user_input}}",
                context, objectMapper, 1_000,
                () -> new ApiException("TOO_LONG", "too long", org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThat(resolved).isEqualTo("A=7; B=7; C=7; U=question");
    }

    @Test
    void preservesUnknownSelectorsAndKeepsExactSelectorValueType() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", List.of(1, 2));
        Map<String, Object> context = Map.of(
                "node_outputs", Map.of("fetch", Map.of("payload", payload)));

        Object typed = WorkflowVariableResolver.resolveTypedTemplate(
                "{{#fetch.payload#}}", context, objectMapper, 1_000,
                () -> new ApiException("TOO_LONG", "too long", org.springframework.http.HttpStatus.BAD_REQUEST));
        Object unknown = WorkflowVariableResolver.resolveTypedTemplate(
                "{{#missing.payload#}}", context, objectMapper, 1_000,
                () -> new ApiException("TOO_LONG", "too long", org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThat(typed).isSameAs(payload);
        assertThat(unknown).isEqualTo("{{#missing.payload#}}");
    }

    @Test
    void enforcesTheFinalExpandedLengthWithoutTruncating() {
        Map<String, Object> context = Map.of("user_input", "x".repeat(20));

        assertThatThrownBy(() -> WorkflowVariableResolver.resolveTextTemplate(
                "{{user_input}}", context, objectMapper, 10,
                () -> new ApiException("TOO_LONG", "too long", org.springframework.http.HttpStatus.BAD_REQUEST)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TOO_LONG"));
    }
}
