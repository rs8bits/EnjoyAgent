package com.enjoy.agent.workflow.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime budgets that bound a single workflow execution, including loop-expanded calls. */
@ConfigurationProperties(prefix = "enjoy.workflow.runtime")
@Validated
public class WorkflowRuntimeProperties {

    @Min(1)
    @Max(100)
    private int maxModelCalls = 20;

    @Min(1)
    @Max(1_000_000)
    private int maxTotalModelTokens = 100_000;

    @Min(1)
    @Max(1800)
    private int maxDurationSeconds = 300;

    @Min(1)
    @Max(100)
    private int maxToolCalls = 20;

    @Min(1)
    @Max(1_000_000)
    private int maxCumulativeToolResultChars = 100_000;

    @Min(1)
    @Max(1_000_000)
    private int maxPromptTokenUpperBoundPerCall = 250_000;

    @Min(1)
    @Max(100_000)
    private int defaultMaxOutputTokens = 4096;

    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
    public int getMaxTotalModelTokens() { return maxTotalModelTokens; }
    public void setMaxTotalModelTokens(int maxTotalModelTokens) { this.maxTotalModelTokens = maxTotalModelTokens; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { this.maxToolCalls = value; }
    public int getMaxCumulativeToolResultChars() { return maxCumulativeToolResultChars; }
    public void setMaxCumulativeToolResultChars(int value) { this.maxCumulativeToolResultChars = value; }
    public int getMaxPromptTokenUpperBoundPerCall() { return maxPromptTokenUpperBoundPerCall; }
    public void setMaxPromptTokenUpperBoundPerCall(int value) { this.maxPromptTokenUpperBoundPerCall = value; }
    public int getDefaultMaxOutputTokens() { return defaultMaxOutputTokens; }
    public void setDefaultMaxOutputTokens(int value) { this.defaultMaxOutputTokens = value; }
}
