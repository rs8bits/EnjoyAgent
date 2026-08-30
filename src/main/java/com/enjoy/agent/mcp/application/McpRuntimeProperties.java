package com.enjoy.agent.mcp.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MCP 运行时治理参数。
 */
@ConfigurationProperties(prefix = "enjoy.mcp.runtime")
@Validated
public class McpRuntimeProperties {

    /**
     * 单个聊天回合最多执行工具的轮数；达到上限后仍允许一次不带工具的最终模型响应。
     */
    @Min(0)
    @Max(20)
    private int maxToolRounds = 3;

    /**
     * 单次工具结果注入模型上下文的最大字符数。
     */
    @Min(1)
    @Max(100_000)
    private int maxToolResultChars = 4000;

    /**
     * 连接远端 MCP server 的超时时间。
     */
    @Min(1)
    @Max(60)
    private int httpConnectTimeoutSeconds = 10;

    /**
     * 读取远端 MCP server 响应的超时时间。
     */
    @Min(1)
    @Max(600)
    private int httpReadTimeoutSeconds = 60;

    @Min(1)
    @Max(1_000_000)
    private int maxToolResponseChars = 100_000;

    /** Raw HTTP response cap applied before JSON/SSE aggregation. */
    @Min(1)
    @Max(10_000_000)
    private int maxHttpResponseBytes = 1_048_576;

    @Min(1)
    @Max(100)
    private int maxToolCallsPerTurn = 20;

    @Min(1)
    @Max(1_000_000)
    private int maxCumulativeToolResultChars = 20_000;

    @Min(1)
    @Max(1_000_000)
    private int maxCumulativeModelTokensPerTurn = 100_000;

    @Min(1)
    @Max(100_000)
    private int defaultMaxOutputTokensPerModelCall = 4096;

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    public int getHttpConnectTimeoutSeconds() {
        return httpConnectTimeoutSeconds;
    }

    public void setHttpConnectTimeoutSeconds(int httpConnectTimeoutSeconds) {
        this.httpConnectTimeoutSeconds = httpConnectTimeoutSeconds;
    }

    public int getHttpReadTimeoutSeconds() {
        return httpReadTimeoutSeconds;
    }

    public void setHttpReadTimeoutSeconds(int httpReadTimeoutSeconds) {
        this.httpReadTimeoutSeconds = httpReadTimeoutSeconds;
    }

    public int getMaxToolResponseChars() { return maxToolResponseChars; }
    public void setMaxToolResponseChars(int value) { this.maxToolResponseChars = value; }
    public int getMaxHttpResponseBytes() { return maxHttpResponseBytes; }
    public void setMaxHttpResponseBytes(int value) { this.maxHttpResponseBytes = value; }
    public int getMaxToolCallsPerTurn() { return maxToolCallsPerTurn; }
    public void setMaxToolCallsPerTurn(int value) { this.maxToolCallsPerTurn = value; }
    public int getMaxCumulativeToolResultChars() { return maxCumulativeToolResultChars; }
    public void setMaxCumulativeToolResultChars(int value) { this.maxCumulativeToolResultChars = value; }
    public int getMaxCumulativeModelTokensPerTurn() { return maxCumulativeModelTokensPerTurn; }
    public void setMaxCumulativeModelTokensPerTurn(int value) { this.maxCumulativeModelTokensPerTurn = value; }
    public int getDefaultMaxOutputTokensPerModelCall() { return defaultMaxOutputTokensPerModelCall; }
    public void setDefaultMaxOutputTokensPerModelCall(int value) { this.defaultMaxOutputTokensPerModelCall = value; }
}
