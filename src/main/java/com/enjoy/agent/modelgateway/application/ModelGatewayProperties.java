package com.enjoy.agent.modelgateway.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model Gateway 基础配置。
 */
@ConfigurationProperties(prefix = "enjoy.model-gateway")
public class ModelGatewayProperties {

    /**
     * OpenAI 兼容接口的基础地址。
     */
    private String openaiBaseUrl = "https://api.openai.com";

    /**
     * 平台托管的 OpenAI API Key。
     */
    private String platformOpenaiApiKey;

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

    /**
     * 兼容不同风格的 OpenAI 兼容地址写法。
     * 如果用户误把 /v1 或完整 chat/completions 路径写进来，这里统一裁掉，
     * 交给 Spring AI 自己去拼接最终请求路径。
     */
    private String normalizeOpenaiBaseUrl(String rawBaseUrl) {
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
