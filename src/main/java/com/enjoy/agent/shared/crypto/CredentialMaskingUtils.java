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

        // 短秘密没有足够的不可见区间，展示首尾会等价于展示全部内容。例如旧算法
        // 对 "abcd" 会返回 "ab****cd"。宁可降低可识别性，也不能泄漏每一个字符。
        if (length <= 8) {
            return "****";
        }

        // 中等长度只展示首尾各 2 位；典型长 API Key 才各展示 4 位。无论哪种情况，
        // 中间始终至少保留 5 个真实字符不可见。
        int visibleLength = length >= 16 ? 4 : 2;
        int prefixLength = visibleLength;
        int suffixLength = visibleLength;

        String prefix = normalized.substring(0, prefixLength);
        String suffix = normalized.substring(length - suffixLength);
        return prefix + "****" + suffix;
    }
}
