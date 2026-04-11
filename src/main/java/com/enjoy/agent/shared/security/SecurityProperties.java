package com.enjoy.agent.shared.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关配置。
 */
@ConfigurationProperties(prefix = "enjoy.security")
public class SecurityProperties {

    private String jwtSecret;
    private long accessTokenExpirationSeconds = 7200;

    /**
     * 启动时校验 JWT 密钥长度，避免弱密钥导致运行时报错。
     */
    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("enjoy.security.jwt-secret must be at least 32 bytes long");
        }
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public void setAccessTokenExpirationSeconds(long accessTokenExpirationSeconds) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }
}
