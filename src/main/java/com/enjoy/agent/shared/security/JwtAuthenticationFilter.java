package com.enjoy.agent.shared.security;

import com.enjoy.agent.shared.tenant.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.slf4j.MDC;
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
    private final AuthenticationSnapshotService authenticationSnapshotService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            AuthenticationSnapshotService authenticationSnapshotService,
            AuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationSnapshotService = authenticationSnapshotService;
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
            AuthenticatedUser authenticatedUser = authenticationSnapshotService.load(
                    claims.tenantId(),
                    claims.userId()
            );

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    authenticatedUser,
                    token,
                    authenticatedUser.authorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            TenantContext.setTenantId(authenticatedUser.tenantId());
            MDC.put("tenantId", String.valueOf(authenticatedUser.tenantId()));

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
            MDC.remove("tenantId");
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

}
