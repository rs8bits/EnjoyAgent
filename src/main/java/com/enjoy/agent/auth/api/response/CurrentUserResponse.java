package com.enjoy.agent.auth.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前登录用户信息。
 */
@Schema(name = "CurrentUserResponse", description = "当前登录用户及其默认租户信息")
public record CurrentUserResponse(
        @Schema(description = "用户 ID", example = "1")
        Long userId,
        @Schema(description = "邮箱", example = "alice@example.com")
        String email,
        @Schema(description = "显示名", example = "Alice")
        String displayName,
        @Schema(description = "租户 ID", example = "1")
        Long tenantId,
        @Schema(description = "租户编码", example = "alice-studio-6e1f7f")
        String tenantCode,
        @Schema(description = "租户名称", example = "Alice Studio")
        String tenantName,
        @Schema(description = "当前角色", example = "OWNER")
        String role,
        @Schema(description = "平台系统角色", example = "USER")
        String systemRole
) {
}
