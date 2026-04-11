package com.enjoy.agent.auth.application;

import com.enjoy.agent.auth.api.request.LoginRequest;
import com.enjoy.agent.auth.api.request.RegisterRequest;
import com.enjoy.agent.auth.api.response.AuthResponse;
import com.enjoy.agent.auth.api.response.CurrentUserResponse;
import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.domain.enums.UserStatus;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.shared.security.IssuedAccessToken;
import com.enjoy.agent.shared.security.JwtTokenProvider;
import com.enjoy.agent.tenant.application.TenantCodeGenerator;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.entity.TenantMember;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberStatus;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantMemberRepository;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务。
 */
@Service
public class AuthApplicationService {

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final TenantMemberRepository tenantMemberRepository;
    private final TenantCodeGenerator tenantCodeGenerator;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthApplicationService(
            AppUserRepository appUserRepository,
            TenantRepository tenantRepository,
            TenantMemberRepository tenantMemberRepository,
            TenantCodeGenerator tenantCodeGenerator,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.tenantMemberRepository = tenantMemberRepository;
        this.tenantCodeGenerator = tenantCodeGenerator;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 注册用户，并创建一个默认租户和 OWNER 身份。
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (appUserRepository.existsByEmail(email)) {
            throw new ApiException("EMAIL_ALREADY_EXISTS", "Email is already registered", HttpStatus.CONFLICT);
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(normalizeText(request.displayName()));
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(SystemRole.USER);
        appUserRepository.save(user);

        String tenantName = resolveTenantName(request);
        Tenant tenant = new Tenant();
        tenant.setCode(tenantCodeGenerator.generate(tenantName));
        tenant.setName(tenantName);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenantRepository.save(tenant);

        TenantMember member = new TenantMember();
        member.setTenant(tenant);
        member.setUser(user);
        member.setRole(TenantMemberRole.OWNER);
        member.setStatus(TenantMemberStatus.ACTIVE);
        tenantMemberRepository.save(member);

        return buildAuthResponse(member);
    }

    /**
     * 使用邮箱和密码登录。
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException("USER_DISABLED", "User is disabled", HttpStatus.FORBIDDEN);
        }

        TenantMember member = tenantMemberRepository.findFirstByUser_IdAndStatusOrderByIdAsc(user.getId(), TenantMemberStatus.ACTIVE)
                .orElseThrow(() -> new ApiException("TENANT_MEMBERSHIP_NOT_FOUND", "No active tenant membership found", HttpStatus.FORBIDDEN));

        validateMembership(member);
        return buildAuthResponse(member);
    }

    /**
     * 读取当前请求中的登录用户。
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser() {
        return toCurrentUserResponse(CurrentUserContext.requireCurrentUser());
    }

    /**
     * 把租户成员关系转换成认证结果。
     */
    private AuthResponse buildAuthResponse(TenantMember member) {
        validateMembership(member);

        AuthenticatedUser authenticatedUser = toAuthenticatedUser(member);
        IssuedAccessToken accessToken = jwtTokenProvider.issueToken(authenticatedUser);

        return new AuthResponse(
                accessToken.accessToken(),
                "Bearer",
                accessToken.expiresAt(),
                toCurrentUserResponse(authenticatedUser)
        );
    }

    /**
     * 生成过滤器和上下文中共用的当前用户对象。
     */
    private AuthenticatedUser toAuthenticatedUser(TenantMember member) {
        return new AuthenticatedUser(
                member.getUser().getId(),
                member.getUser().getEmail(),
                member.getUser().getDisplayName(),
                member.getTenant().getId(),
                member.getTenant().getCode(),
                member.getTenant().getName(),
                member.getRole(),
                member.getUser().getSystemRole()
        );
    }

    /**
     * 转成接口响应使用的用户信息。
     */
    private CurrentUserResponse toCurrentUserResponse(AuthenticatedUser authenticatedUser) {
        return new CurrentUserResponse(
                authenticatedUser.userId(),
                authenticatedUser.email(),
                authenticatedUser.displayName(),
                authenticatedUser.tenantId(),
                authenticatedUser.tenantCode(),
                authenticatedUser.tenantName(),
                authenticatedUser.role().name(),
                authenticatedUser.systemRole().name()
        );
    }

    /**
     * 校验用户、租户、成员关系是否都处于可用状态。
     */
    private void validateMembership(TenantMember member) {
        if (member.getStatus() != TenantMemberStatus.ACTIVE) {
            throw new ApiException("TENANT_MEMBERSHIP_DISABLED", "Tenant membership is disabled", HttpStatus.FORBIDDEN);
        }
        if (member.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException("TENANT_DISABLED", "Tenant is disabled", HttpStatus.FORBIDDEN);
        }
        if (member.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ApiException("USER_DISABLED", "User is disabled", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 优先使用请求里的租户名，否则按“显示名 + Workspace”生成。
     */
    private String resolveTenantName(RegisterRequest request) {
        if (request.tenantName() != null && !request.tenantName().isBlank()) {
            return normalizeText(request.tenantName());
        }
        return normalizeText(request.displayName()) + " Workspace";
    }

    /**
     * 统一做邮箱归一化，避免大小写导致重复账号。
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 去除前后空格，保持文本存储整洁。
     */
    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }
}
