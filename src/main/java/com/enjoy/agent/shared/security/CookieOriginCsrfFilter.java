package com.enjoy.agent.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SameSite is the primary cookie CSRF boundary; this filter additionally requires an approved
 * browser Origin for state-changing requests that authenticated via the access-token cookie.
 */
@Component
public class CookieOriginCsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SecurityProperties properties;
    private final AccessDeniedHandler accessDeniedHandler;

    public CookieOriginCsrfFilter(SecurityProperties properties, AccessDeniedHandler accessDeniedHandler) {
        this.properties = properties;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())
                || !Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.COOKIE_AUTHENTICATION_ATTRIBUTE))) {
            filterChain.doFilter(request, response);
            return;
        }

        String origin = normalizeOrigin(request.getHeader("Origin"));
        if (origin != null && isAllowed(origin, request)) {
            filterChain.doFilter(request, response);
            return;
        }

        accessDeniedHandler.handle(
                request,
                response,
                new AccessDeniedException("Cookie-authenticated write requires an approved Origin")
        );
    }

    private boolean isAllowed(String origin, HttpServletRequest request) {
        if (origin.equals(requestOrigin(request))) {
            return true;
        }
        return properties.getAllowedBrowserOrigins().stream()
                .map(this::normalizeOrigin)
                .anyMatch(origin::equals);
    }

    private String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme().toLowerCase(Locale.ROOT);
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private String normalizeOrigin(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
