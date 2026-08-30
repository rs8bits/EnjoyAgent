package com.enjoy.agent.chat.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.shared.exception.ApiException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;

/** Conservative pre-call token budget for every model round in one MCP chat turn. */
final class McpModelTokenBudget {

    private final int maximum;
    private int committed;
    private int reserved;

    McpModelTokenBudget(int maximum) {
        this.maximum = maximum;
    }

    synchronized Permit beforeCall(int promptTokenUpperBound, int maxOutputTokens) {
        int prompt = Math.max(1, promptTokenUpperBound);
        int output = Math.max(1, maxOutputTokens);
        long requested = (long) prompt + output;
        if ((long) committed + reserved + requested > maximum) {
            throw exceeded();
        }
        reserved += Math.toIntExact(requested);
        return new Permit(prompt, output);
    }

    synchronized void record(Permit permit, ModelGatewayChatCompletion completion) {
        if (!permit.settle()) {
            throw new IllegalStateException("MCP model token permit was already settled");
        }
        reserved -= permit.reservedTokens();
        int prompt = completion.promptTokens() == null
                ? permit.promptUpperBound() : Math.max(0, completion.promptTokens());
        int output = completion.completionTokens() == null
                ? permit.maxOutputTokens() : Math.max(0, completion.completionTokens());
        committed = Math.addExact(committed, Math.addExact(prompt, output));
        if (committed + reserved > maximum) {
            throw exceeded();
        }
    }

    synchronized void release(Permit permit) {
        if (permit != null && permit.settle()) {
            reserved -= permit.reservedTokens();
        }
    }

    synchronized int committedTokens() { return committed; }

    private ApiException exceeded() {
        return new ApiException(
                "MCP_MODEL_TOKEN_BUDGET_EXCEEDED",
                "Cumulative model usage exceeded the MCP turn budget",
                HttpStatus.BAD_REQUEST);
    }

    static final class Permit {
        private final int promptUpperBound;
        private final int maxOutputTokens;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Permit(int promptUpperBound, int maxOutputTokens) {
            this.promptUpperBound = promptUpperBound;
            this.maxOutputTokens = maxOutputTokens;
        }

        int promptUpperBound() { return promptUpperBound; }
        int maxOutputTokens() { return maxOutputTokens; }
        int reservedTokens() { return promptUpperBound + maxOutputTokens; }
        boolean settle() { return settled.compareAndSet(false, true); }
    }
}
