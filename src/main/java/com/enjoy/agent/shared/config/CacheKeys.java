package com.enjoy.agent.shared.config;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import java.util.Objects;

/**
 * 统一生成带租户边界的缓存 Key，避免缓存命中绕过方法体内的租户校验。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String agent(Long tenantId, Long agentId) {
        return required(tenantId, "tenantId") + ":" + required(agentId, "agentId");
    }

    public static String authenticationContext(Long tenantId, Long userId) {
        return required(tenantId, "tenantId") + ":" + required(userId, "userId");
    }

    public static String sessionSummary(Long tenantId, Long sessionId, MemoryStrategy memoryStrategy) {
        return required(tenantId, "tenantId")
                + ":" + required(sessionId, "sessionId")
                + ":" + Objects.toString(memoryStrategy, "NONE");
    }

    private static Long required(Long value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }
}
