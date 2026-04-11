package com.enjoy.agent.auth.api;

import com.enjoy.agent.auth.api.request.LoginRequest;
import com.enjoy.agent.auth.api.request.RegisterRequest;
import com.enjoy.agent.auth.api.response.AuthResponse;
import com.enjoy.agent.auth.api.response.CurrentUserResponse;
import com.enjoy.agent.auth.application.AuthApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证相关接口。
 */
@Tag(name = "认证", description = "注册、登录和当前用户信息接口")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    public AuthController(AuthApplicationService authApplicationService) {
        this.authApplicationService = authApplicationService;
    }

    /**
     * 注册新用户，并自动创建默认租户和租户成员关系。
     */
    @Operation(summary = "用户注册", description = "创建用户、默认租户和 OWNER 角色的租户成员关系，并直接返回 JWT", security = {})
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authApplicationService.register(request), "Registration succeeded");
    }

    /**
     * 使用邮箱和密码完成登录。
     */
    @Operation(summary = "用户登录", description = "校验邮箱和密码，并返回 JWT", security = {})
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authApplicationService.login(request), "Login succeeded");
    }

    /**
     * 返回当前登录用户的信息。
     */
    @Operation(summary = "当前用户", description = "读取 Bearer Token 对应的当前登录用户信息")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me() {
        return ApiResponse.success(authApplicationService.currentUser());
    }
}
