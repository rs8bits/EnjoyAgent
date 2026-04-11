package com.enjoy.agent.auth.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求参数。
 */
@Schema(name = "RegisterRequest", description = "注册请求参数")
public record RegisterRequest(
        @Schema(description = "登录邮箱", example = "alice@example.com")
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Schema(description = "登录密码", example = "Passw0rd1")
        @NotBlank
        @Size(min = 8, max = 100)
        String password,

        @Schema(description = "用户显示名", example = "Alice")
        @NotBlank
        @Size(max = 128)
        String displayName,

        @Schema(description = "租户名称，不传时会自动按“显示名 + Workspace”生成", example = "Alice Studio")
        @Size(max = 128)
        String tenantName
) {
}
