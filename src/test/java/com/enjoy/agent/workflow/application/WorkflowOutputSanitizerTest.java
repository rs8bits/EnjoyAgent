package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowOutputSanitizerTest {

    private final WorkflowOutputSanitizer sanitizer = new WorkflowOutputSanitizer();

    @Test
    void deeplyRemovesRawToolResponsesPromptsAndNestedCredentials() {
        Map<String, Object> source = Map.of(
                "system_prompt", "private prompt",
                "mcp_tool_approval_tokens", List.of("signed-token"),
                "tool_raw_response", "raw secret payload",
                "node_outputs", Map.of(
                        "tool", Map.of(
                                "authorization", "Bearer abc.def",
                                "tool_result", "{\"api_key\":\"super-secret\",\"ok\":true}"
                        )
                )
        );

        Map<String, Object> safe = sanitizer.sanitizeMap(source);

        assertThat(safe).doesNotContainKeys(
                "system_prompt", "mcp_tool_approval_tokens", "tool_raw_response");
        assertThat(safe.toString())
                .doesNotContain("private prompt", "signed-token", "raw secret payload", "abc.def", "super-secret")
                .contains("[REDACTED]");
    }
}
