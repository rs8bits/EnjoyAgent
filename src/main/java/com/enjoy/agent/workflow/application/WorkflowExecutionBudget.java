package com.enjoy.agent.workflow.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.shared.exception.ApiException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;

/** Thread-safe execution budget shared by top-level and loop-body nodes. */
public final class WorkflowExecutionBudget {

    private final int maxModelCalls;
    private final int maxTotalModelTokens;
    private final int maxToolCalls;
    private final int maxCumulativeToolResultChars;
    private final int maxPromptTokenUpperBoundPerCall;
    private final int defaultMaxOutputTokens;
    private final long deadlineNanos;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();
    private final AtomicInteger reservedModelTokens = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger cumulativeToolResultChars = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public WorkflowExecutionBudget(WorkflowRuntimeProperties properties) {
        this.maxModelCalls = properties.getMaxModelCalls();
        this.maxTotalModelTokens = properties.getMaxTotalModelTokens();
        this.maxToolCalls = properties.getMaxToolCalls();
        this.maxCumulativeToolResultChars = properties.getMaxCumulativeToolResultChars();
        this.maxPromptTokenUpperBoundPerCall = properties.getMaxPromptTokenUpperBoundPerCall();
        this.defaultMaxOutputTokens = properties.getDefaultMaxOutputTokens();
        this.deadlineNanos = System.nanoTime()
                + Duration.ofSeconds(properties.getMaxDurationSeconds()).toNanos();
    }

    public void checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new ApiException("WORKFLOW_EXECUTION_CANCELLED", "Workflow execution was cancelled", HttpStatus.CONFLICT);
        }
        if (System.nanoTime() > deadlineNanos) {
            throw new ApiException("WORKFLOW_EXECUTION_TIMEOUT", "Workflow execution exceeded its time budget", HttpStatus.REQUEST_TIMEOUT);
        }
    }

    public ModelCallPermit beforeModelCall(int promptTokenUpperBound, Integer configuredMaxOutputTokens) {
        checkActive();
        if (promptTokenUpperBound < 0 || promptTokenUpperBound > maxPromptTokenUpperBoundPerCall) {
            throw new ApiException("WORKFLOW_MODEL_PROMPT_BUDGET_EXCEEDED", "Workflow model prompt exceeded its per-call budget", HttpStatus.BAD_REQUEST);
        }
        int outputTokens = configuredMaxOutputTokens == null
                ? defaultMaxOutputTokens : Math.max(1, configuredMaxOutputTokens);
        int reservation;
        try {
            reservation = Math.addExact(promptTokenUpperBound, outputTokens);
        } catch (ArithmeticException ex) {
            throw new ApiException("WORKFLOW_MODEL_TOKEN_BUDGET_EXCEEDED", "Workflow model token reservation is too large", HttpStatus.BAD_REQUEST);
        }
        reserveModelTokens(reservation);
        int calls = modelCalls.incrementAndGet();
        if (calls > maxModelCalls) {
            modelCalls.decrementAndGet();
            reservedModelTokens.addAndGet(-reservation);
            throw new ApiException("WORKFLOW_MODEL_CALL_BUDGET_EXCEEDED", "Workflow exceeded its model-call budget", HttpStatus.BAD_REQUEST);
        }
        return new ModelCallPermit(promptTokenUpperBound, outputTokens);
    }

    /** Called only after the real model response has been durably accounted. */
    public void recordModelUsage(ModelCallPermit permit, ModelGatewayChatCompletion completion) {
        if (!permit.settle()) {
            throw new IllegalStateException("Workflow model-call permit was already settled");
        }
        reservedModelTokens.addAndGet(-permit.reservedTokens());
        int prompt = completion.promptTokens() == null
                ? permit.promptTokensUpperBound() : nonNegative(completion.promptTokens());
        int response = completion.completionTokens() == null
                ? permit.maxOutputTokens() : nonNegative(completion.completionTokens());
        int accumulated = promptTokens.addAndGet(prompt) + completionTokens.addAndGet(response);
        if ((long) accumulated + reservedModelTokens.get() > maxTotalModelTokens) {
            throw new ApiException("WORKFLOW_MODEL_TOKEN_BUDGET_EXCEEDED", "Workflow exceeded its cumulative model-token budget", HttpStatus.BAD_REQUEST);
        }
        checkActive();
    }

    public void releaseModelCall(ModelCallPermit permit) {
        if (permit != null && permit.settle()) {
            reservedModelTokens.addAndGet(-permit.reservedTokens());
        }
    }

    public void beforeToolCall() {
        checkActive();
        if (toolCalls.incrementAndGet() > maxToolCalls) {
            toolCalls.decrementAndGet();
            throw new ApiException("WORKFLOW_TOOL_CALL_BUDGET_EXCEEDED", "Workflow exceeded its tool-call budget", HttpStatus.BAD_REQUEST);
        }
    }

    public void recordToolResult(String modelVisibleContent, String rawResponsePayload) {
        int chars = Math.max(length(modelVisibleContent), length(rawResponsePayload));
        if (cumulativeToolResultChars.addAndGet(chars) > maxCumulativeToolResultChars) {
            throw new ApiException("WORKFLOW_TOOL_RESULT_BUDGET_EXCEEDED", "Workflow exceeded its cumulative tool-result budget", HttpStatus.BAD_REQUEST);
        }
        checkActive();
    }

    public int resolveMaxOutputTokens(Integer configured) {
        return configured == null ? defaultMaxOutputTokens : configured;
    }

    public void cancel() { cancelled.set(true); }
    public int modelCalls() { return modelCalls.get(); }
    public int promptTokens() { return promptTokens.get(); }
    public int completionTokens() { return completionTokens.get(); }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int length(String value) { return value == null ? 0 : value.length(); }

    private void reserveModelTokens(int reservation) {
        while (true) {
            int currentReserved = reservedModelTokens.get();
            long committed = (long) promptTokens.get() + completionTokens.get();
            if (committed + currentReserved + reservation > maxTotalModelTokens) {
                throw new ApiException("WORKFLOW_MODEL_TOKEN_BUDGET_EXCEEDED", "Workflow exceeded its model-token budget before invocation", HttpStatus.BAD_REQUEST);
            }
            if (reservedModelTokens.compareAndSet(currentReserved, currentReserved + reservation)) {
                return;
            }
        }
    }

    public static final class ModelCallPermit {
        private final int promptTokensUpperBound;
        private final int maxOutputTokens;
        private final AtomicBoolean settled = new AtomicBoolean();

        private ModelCallPermit(int promptTokensUpperBound, int maxOutputTokens) {
            this.promptTokensUpperBound = promptTokensUpperBound;
            this.maxOutputTokens = maxOutputTokens;
        }
        private int promptTokensUpperBound() { return promptTokensUpperBound; }
        private int maxOutputTokens() { return maxOutputTokens; }
        private int reservedTokens() { return promptTokensUpperBound + maxOutputTokens; }
        private boolean settle() { return settled.compareAndSet(false, true); }
    }
}
