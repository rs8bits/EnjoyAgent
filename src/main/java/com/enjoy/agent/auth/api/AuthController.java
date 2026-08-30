package com.enjoy.agent.auth.api;

import com.enjoy.agent.auth.api.request.LoginRequest;
import com.enjoy.agent.auth.api.request.RegisterRequest;
import com.enjoy.agent.auth.api.response.AuthResponse;
import com.enjoy.agent.auth.api.response.CurrentUserResponse;
import com.enjoy.agent.auth.application.AuthApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.shared.security.AccessTokenCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
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
    private final AccessTokenCookieService accessTokenCookieService;

    public AuthController(
            AuthApplicationService authApplicationService,
            AccessTokenCookieService accessTokenCookieService
    ) {
        this.authApplicationService = authApplicationService;
        this.accessTokenCookieService = accessTokenCookieService;
    }

    /**
     * 注册新用户，并自动创建默认租户和租户成员关系。
     */
    @Operation(summary = "用户注册", description = "创建用户、默认租户和 OWNER 成员关系，并通过 HttpOnly Cookie 建立会话", security = {})
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse servletResponse
    ) {
        AuthResponse auth = authApplicationService.register(request);
        accessTokenCookieService.write(servletResponse, auth.accessToken(), auth.expiresAt());
        return ApiResponse.success(accessTokenCookieService.browserSafeResponse(auth), "Registration succeeded");
    }

    /**
     * 使用邮箱和密码完成登录。
     */
    @Operation(summary = "用户登录", description = "校验邮箱和密码，并通过 HttpOnly Cookie 建立会话", security = {})
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse servletResponse
    ) {
        AuthResponse auth = authApplicationService.login(request);
        accessTokenCookieService.write(servletResponse, auth.accessToken(), auth.expiresAt());
        return ApiResponse.success(accessTokenCookieService.browserSafeResponse(auth), "Login succeeded");
    }

    /**
     * Clears the browser access-token cookie. This endpoint is intentionally idempotent.
     */
    @Operation(summary = "退出登录", description = "清除 HttpOnly access-token cookie", security = {})
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse servletResponse) {
        accessTokenCookieService.clear(servletResponse);
        return ApiResponse.success(null, "Logout succeeded");
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
