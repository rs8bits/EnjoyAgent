package com.enjoy.agent.shared.crypto;

/**
 * 凭证脱敏工具。
 */
public final class CredentialMaskingUtils {

    private CredentialMaskingUtils() {
    }

    /**
     * 生成一个可识别但不会泄漏真实值的脱敏字符串。
     */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "****";
        }

        String normalized = secret.trim();
        int length = normalized.length();
        int prefixLength = length <= 8 ? Math.min(2, length) : Math.min(4, length);
        int suffixLength = length <= 8 ? Math.min(2, Math.max(0, length - prefixLength)) : Math.min(4, length - prefixLength);

        String prefix = normalized.substring(0, prefixLength);
        String suffix = suffixLength == 0 ? "" : normalized.substring(length - suffixLength);
        return prefix + "****" + suffix;
    }
}
