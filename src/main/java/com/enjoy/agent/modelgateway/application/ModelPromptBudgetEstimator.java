package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Conservatively bounds the exact prompt material sent through the model gateway. */
final class ModelPromptBudgetEstimator {

    private static final int STRUCTURAL_OVERHEAD_BYTES = 2048;
    private static final int PER_MESSAGE_OVERHEAD_BYTES = 64;
    private static final int PER_TOOL_OVERHEAD_BYTES = 256;

    private final ModelGatewayProperties properties;

    ModelPromptBudgetEstimator(ModelGatewayProperties properties) {
        this.properties = properties;
    }

    PromptBudget estimate(
            String systemPrompt,
            String sessionMemorySummary,
            String retrievalContext,
            List<ModelGatewayConversationMessage> messages,
            List<ModelGatewayToolDefinition> tools
    ) {
        Counter counter = new Counter();
        counter.add(systemPrompt);
        counter.add(sessionMemorySummary);
        counter.add(retrievalContext);
        for (ModelGatewayConversationMessage message : safe(messages)) {
            counter.addOverhead(PER_MESSAGE_OVERHEAD_BYTES);
            counter.add(message.role());
            counter.add(message.content());
            counter.add(message.toolCallId());
            for (ModelGatewayToolCall toolCall : safe(message.toolCalls())) {
                counter.add(toolCall.id());
                counter.add(toolCall.name());
                counter.add(toolCall.argumentsJson());
            }
        }
        for (ModelGatewayToolDefinition tool : safe(tools)) {
            counter.addOverhead(PER_TOOL_OVERHEAD_BYTES);
            counter.add(tool.name());
            counter.add(tool.description());
            counter.add(tool.inputSchemaJson());
        }
        counter.addOverhead(STRUCTURAL_OVERHEAD_BYTES);
        return new PromptBudget(
                Math.toIntExact(counter.chars),
                Math.toIntExact(counter.bytes),
                Math.toIntExact(counter.bytes)
        );
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    record PromptBudget(int chars, int utf8Bytes, int conservativeInputTokenUpperBound) {
    }

    private final class Counter {
        private long chars;
        private long bytes;

        private void add(String value) {
            if (value == null) {
                return;
            }
            chars += value.length();
            requireWithinLimits();
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            requireWithinLimits();
        }

        private void addOverhead(int overheadBytes) {
            bytes += overheadBytes;
            requireWithinLimits();
        }

        private void requireWithinLimits() {
            if (chars > properties.getMaxPromptChars() || bytes > properties.getMaxPromptBytes()) {
                throw new ApiException(
                        "MODEL_PROMPT_TOO_LARGE",
                        "Model prompt exceeded the configured size limit",
                        HttpStatus.PAYLOAD_TOO_LARGE
                );
            }
        }
    }
}
