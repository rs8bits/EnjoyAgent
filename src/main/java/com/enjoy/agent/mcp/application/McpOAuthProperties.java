package com.enjoy.agent.mcp.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MCP OAuth 运行参数。
 */
@ConfigurationProperties(prefix = "enjoy.mcp.oauth")
@Validated
public class McpOAuthProperties {

    /**
     * OAuth 回调暴露给授权服务器的外部基础地址。
     */
    private String publicBaseUrl = "http://localhost:8080";

    /**
     * OAuth 回调路径。
     */
    private String callbackPath = "/api/mcp/oauth/callback";

    /**
     * 令牌提前刷新的时间窗口。
     */
    @Min(0)
    @Max(3600)
    private long refreshSkewSeconds = 60;

    /**
     * 授权 state 的有效期。
     */
    @Min(60)
    @Max(3600)
    private long authorizationTtlSeconds = 600;

    @Min(1024)
    @Max(10_000_000)
    private int maxTokenResponseBytes = 262_144;

    @Min(1024)
    @Max(10_000_000)
    private int maxMetadataResponseBytes = 1_000_000;

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getCallbackPath() {
        return callbackPath;
    }

    public void setCallbackPath(String callbackPath) {
        this.callbackPath = callbackPath;
    }

    public long getRefreshSkewSeconds() {
        return refreshSkewSeconds;
    }

    public void setRefreshSkewSeconds(long refreshSkewSeconds) {
        this.refreshSkewSeconds = refreshSkewSeconds;
    }

    public long getAuthorizationTtlSeconds() {
        return authorizationTtlSeconds;
    }

    public void setAuthorizationTtlSeconds(long authorizationTtlSeconds) {
        this.authorizationTtlSeconds = authorizationTtlSeconds;
    }

    public int getMaxTokenResponseBytes() { return maxTokenResponseBytes; }
    public void setMaxTokenResponseBytes(int value) { this.maxTokenResponseBytes = value; }
    public int getMaxMetadataResponseBytes() { return maxMetadataResponseBytes; }
    public void setMaxMetadataResponseBytes(int value) { this.maxMetadataResponseBytes = value; }
}
