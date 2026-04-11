package com.enjoy.agent.shared.security;

import java.time.Instant;

/**
 * 已签发的访问令牌结果。
 */
public record IssuedAccessToken(
        String accessToken,
        Instant expiresAt
) {
}
