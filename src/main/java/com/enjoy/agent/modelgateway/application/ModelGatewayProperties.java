package com.enjoy.agent.modelgateway.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Model Gateway 基础配置。
 */
@ConfigurationProperties(prefix = "enjoy.model-gateway")
@Validated
public class ModelGatewayProperties {

    /**
     * OpenAI 兼容接口的基础地址。
     */
    private String openaiBaseUrl = "https://api.openai.com";

    /**
     * 平台托管的 OpenAI API Key。
     */
    private String platformOpenaiApiKey;

    @Min(1)
    @Max(60)
    private int connectTimeoutSeconds = 10;

    @Min(1)
    @Max(1800)
    private int readTimeoutSeconds = 300;

    @Min(1)
    @Max(20_000_000)
    private int maxResponseBytes = 2_000_000;

    @Min(1)
    @Max(5_000_000)
    private int maxResponseChars = 500_000;

    @Min(1)
    @Max(5_000_000)
    private int maxPromptChars = 400_000;

    @Min(1)
    @Max(10_000_000)
    private int maxPromptBytes = 1_000_000;

    public String getOpenaiBaseUrl() {
        return normalizeOpenaiBaseUrl(openaiBaseUrl);
    }

    public void setOpenaiBaseUrl(String openaiBaseUrl) {
        this.openaiBaseUrl = openaiBaseUrl;
    }

    public String getPlatformOpenaiApiKey() {
        return platformOpenaiApiKey;
    }

    public void setPlatformOpenaiApiKey(String platformOpenaiApiKey) {
        this.platformOpenaiApiKey = platformOpenaiApiKey;
    }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int value) { this.connectTimeoutSeconds = value; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int value) { this.readTimeoutSeconds = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
    public int getMaxResponseChars() { return maxResponseChars; }
    public void setMaxResponseChars(int value) { this.maxResponseChars = value; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int value) { this.maxPromptChars = value; }
    public int getMaxPromptBytes() { return maxPromptBytes; }
    public void setMaxPromptBytes(int value) { this.maxPromptBytes = value; }

    /**
     * 兼容不同风格的 OpenAI 兼容地址写法。
     * 如果用户误把 /v1 或完整 chat/completions 路径写进来，这里统一裁掉，
     * 交给 Spring AI 自己去拼接最终请求路径。
     */
    public static String normalizeOpenaiBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return "https://api.openai.com";
        }

        String normalized = rawBaseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/v1/chat/completions".length());
        } else if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        } else if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        return normalized;
    }
}
