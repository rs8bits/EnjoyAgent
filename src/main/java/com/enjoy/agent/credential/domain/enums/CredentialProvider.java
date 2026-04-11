package com.enjoy.agent.credential.domain.enums;

/**
 * 凭证提供方枚举。
 */
public enum CredentialProvider {
    OPENAI,
    ANTHROPIC,
    DASHSCOPE,
    DEEPSEEK,
    OPENROUTER,
    CUSTOM;

    public boolean isOpenAiCompatibleRuntime() {
        return this == OPENAI || this == DASHSCOPE;
    }

    public String defaultCompatibleBaseUrl() {
        return switch (this) {
            case OPENAI -> "https://api.openai.com";
            case DASHSCOPE -> "https://dashscope.aliyuncs.com/compatible-mode";
            default -> null;
        };
    }
}
