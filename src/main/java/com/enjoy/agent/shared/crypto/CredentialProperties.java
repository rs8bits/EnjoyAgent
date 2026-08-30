package com.enjoy.agent.shared.crypto;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证加密配置。
 */
@ConfigurationProperties(prefix = "enjoy.credential")
public class CredentialProperties {

    private static final String INSECURE_DEVELOPMENT_KEY = "0123456789abcdef0123456789abcdef";

    private String aesKey;
    private List<String> previousAesKeys = new ArrayList<>();
    private boolean allowInsecureDevelopmentSecret;

    /**
     * 启动时校验 AES 密钥长度。
     */
    @PostConstruct
    public void validate() {
        validateKey(aesKey, "aes-key");
        for (String previousKey : previousAesKeys) {
            validateKey(previousKey, "previous-aes-keys");
        }
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }

    public List<String> getPreviousAesKeys() {
        return List.copyOf(previousAesKeys);
    }

    public void setPreviousAesKeys(List<String> previousAesKeys) {
        this.previousAesKeys = previousAesKeys == null
                ? new ArrayList<>()
                : previousAesKeys.stream().filter(key -> key != null && !key.isBlank()).distinct().toList();
    }

    public boolean isAllowInsecureDevelopmentSecret() {
        return allowInsecureDevelopmentSecret;
    }

    public void setAllowInsecureDevelopmentSecret(boolean allowInsecureDevelopmentSecret) {
        this.allowInsecureDevelopmentSecret = allowInsecureDevelopmentSecret;
    }

    private void validateKey(String key, String propertyName) {
        int keyLength = key == null ? 0 : key.getBytes(StandardCharsets.UTF_8).length;
        if (keyLength != 16 && keyLength != 24 && keyLength != 32) {
            throw new IllegalStateException(
                    "enjoy.credential." + propertyName + " entries must be 16, 24, or 32 bytes long");
        }
        if (!allowInsecureDevelopmentSecret && INSECURE_DEVELOPMENT_KEY.equals(key)) {
            throw new IllegalStateException(
                    "enjoy.credential." + propertyName + " must not use the development default");
        }
    }
}
