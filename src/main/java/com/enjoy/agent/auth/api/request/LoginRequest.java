package com.enjoy.agent.auth.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求参数。
 */
@Schema(name = "LoginRequest", description = "登录请求参数")
public record LoginRequest(
        @Schema(description = "登录邮箱", example = "alice@example.com")
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Schema(description = "登录密码", example = "Passw0rd1")
        @NotBlank
        @Size(min = 8, max = 100)
        String password
) {
}
