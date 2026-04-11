package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.domain.enums.UserStatus;
import com.enjoy.agent.shared.tenant.TenantContext;
import com.enjoy.agent.tenant.domain.entity.TenantMember;
import com.enjoy.agent.tenant.domain.enums.TenantMemberStatus;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantMemberRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * JWT 认证过滤器。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TenantMemberRepository tenantMemberRepository;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            TenantMemberRepository tenantMemberRepository,
            AuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tenantMemberRepository = tenantMemberRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    /**
     * 从请求头读取 Bearer Token，并建立当前请求的认证上下文。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }

            JwtTokenClaims claims = jwtTokenProvider.parseToken(token);
            TenantMember member = tenantMemberRepository.findByTenant_IdAndUser_Id(claims.tenantId(), claims.userId())
                    .orElseThrow(() -> new BadCredentialsException("Authentication context not found"));

            validateMember(member);

            AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                    member.getUser().getId(),
                    member.getUser().getEmail(),
                    member.getUser().getDisplayName(),
                    member.getTenant().getId(),
                    member.getTenant().getCode(),
                    member.getTenant().getName(),
                    member.getRole(),
                    member.getUser().getSystemRole()
            );

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    authenticatedUser,
                    token,
                    authenticatedUser.authorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            TenantContext.setTenantId(authenticatedUser.tenantId());

            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Invalid or expired access token", ex)
            );
        } catch (BadCredentialsException ex) {
            authenticationEntryPoint.commence(request, response, ex);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从 Authorization 头里提取 Bearer Token。
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    /**
     * 再次校验数据库里的用户、租户和成员关系状态，避免旧 token 越权。
     */
    private void validateMember(TenantMember member) {
        if (member.getStatus() != TenantMemberStatus.ACTIVE) {
            throw new BadCredentialsException("Tenant membership is disabled");
        }
        if (member.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BadCredentialsException("User is disabled");
        }
        if (member.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new BadCredentialsException("Tenant is disabled");
        }
    }
}
