package com.enjoy.agent.auth.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 注册或登录成功后的响应体。
 */
@Schema(name = "AuthResponse", description = "注册或登录成功后的认证结果")
public record AuthResponse(
        @Schema(description = "JWT 访问令牌")
        String accessToken,
        @Schema(description = "令牌类型", example = "Bearer")
        String tokenType,
        @Schema(description = "令牌过期时间")
        Instant expiresAt,
        @Schema(description = "当前登录用户信息")
        CurrentUserResponse currentUser
) {
}
