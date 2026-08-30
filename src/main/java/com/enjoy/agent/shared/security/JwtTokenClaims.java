package com.enjoy.agent.shared.security;

/**
 * JWT 只携带定位数据库安全快照所需的不可变标识；角色、状态和展示信息均不信任令牌。
 */
public record JwtTokenClaims(
        Long userId,
        Long tenantId
) {
}
