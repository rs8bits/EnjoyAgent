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

    public static final String COOKIE_AUTHENTICATION_ATTRIBUTE =
            JwtAuthenticationFilter.class.getName() + ".COOKIE_AUTHENTICATION";

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationSnapshotService authenticationSnapshotService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessTokenCookieService accessTokenCookieService;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            AuthenticationSnapshotService authenticationSnapshotService,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessTokenCookieService accessTokenCookieService
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationSnapshotService = authenticationSnapshotService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessTokenCookieService = accessTokenCookieService;
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
            ResolvedToken resolvedToken = resolveToken(request);
            if (resolvedToken == null) {
                filterChain.doFilter(request, response);
                return;
            }

            JwtTokenClaims claims;
            AuthenticatedUser authenticatedUser;
            try {
                claims = jwtTokenProvider.parseToken(resolvedToken.value());
                authenticatedUser = authenticationSnapshotService.load(
                        claims.tenantId(),
                        claims.userId()
                );
            } catch (JwtException | IllegalArgumentException ex) {
                authenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException("Invalid or expired access token", ex)
                );
                return;
            } catch (BadCredentialsException ex) {
                authenticationEntryPoint.commence(request, response, ex);
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    authenticatedUser,
                    resolvedToken.value(),
                    authenticatedUser.authorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(COOKIE_AUTHENTICATION_ATTRIBUTE, resolvedToken.cookieBased());
            TenantContext.setTenantId(authenticatedUser.tenantId());
            MDC.put("tenantId", String.valueOf(authenticatedUser.tenantId()));

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("tenantId");
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/auth/login".equals(path)
                || "/api/auth/register".equals(path)
                || "/api/auth/logout".equals(path);
    }

    /**
     * 从 Authorization 头里提取 Bearer Token。
     */
    private ResolvedToken resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : new ResolvedToken(token, false);
        }
        String cookieToken = accessTokenCookieService.read(request);
        return cookieToken == null ? null : new ResolvedToken(cookieToken, true);
    }

    private record ResolvedToken(String value, boolean cookieBased) {
    }
}
