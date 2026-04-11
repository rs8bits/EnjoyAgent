package com.enjoy.agent.shared.crypto;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证加密配置。
 */
@ConfigurationProperties(prefix = "enjoy.credential")
public class CredentialProperties {

    private String aesKey;

    /**
     * 启动时校验 AES 密钥长度。
     */
    @PostConstruct
    public void validate() {
        int keyLength = aesKey == null ? 0 : aesKey.getBytes(StandardCharsets.UTF_8).length;
        if (keyLength != 16 && keyLength != 24 && keyLength != 32) {
            throw new IllegalStateException("enjoy.credential.aes-key must be 16, 24, or 32 bytes long");
        }
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }
}
