package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;

/**
 * JWT 里关心的业务声明。
 */
public record JwtTokenClaims(
        Long userId,
        Long tenantId,
        String email,
        String displayName,
        String tenantCode,
        String tenantName,
        TenantMemberRole role,
        SystemRole systemRole
) {
}
