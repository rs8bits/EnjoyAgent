package com.enjoy.agent.shared.tenant;

import java.util.Optional;

/**
 * 当前请求的租户上下文。
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 写入当前请求的租户 ID。
     */
    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * 获取当前请求的租户 ID。
     */
    public static Optional<Long> getTenantId() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    /**
     * 请求结束后清理上下文，避免线程复用导致串租户。
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
