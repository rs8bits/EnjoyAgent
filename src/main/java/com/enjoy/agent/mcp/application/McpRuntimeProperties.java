package com.enjoy.agent.mcp.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 运行时治理参数。
 */
@ConfigurationProperties(prefix = "enjoy.mcp.runtime")
public class McpRuntimeProperties {

    /**
     * 模型与工具往返的最大轮数。
     */
    private int maxToolRounds = 3;

    /**
     * 单次工具结果注入模型上下文的最大字符数。
     */
    private int maxToolResultChars = 4000;

    /**
     * 连接远端 MCP server 的超时时间。
     */
    private int httpConnectTimeoutSeconds = 10;

    /**
     * 读取远端 MCP server 响应的超时时间。
     */
    private int httpReadTimeoutSeconds = 60;

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
}
