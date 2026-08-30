package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.api.response.AuthResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Delivers browser access tokens in an HttpOnly cookie while retaining an optional body token for
 * CLI/performance clients in explicitly enabled development profiles.
 */
@Component
public class AccessTokenCookieService {

    private final SecurityProperties properties;
    private final Clock clock;

    public AccessTokenCookieService(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void write(HttpServletResponse response, String token, Instant expiresAt) {
        long maxAgeSeconds = Math.max(0, Duration.between(Instant.now(clock), expiresAt).toSeconds());
        ResponseCookie cookie = baseCookie(token)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.getAccessTokenCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    public AuthResponse browserSafeResponse(AuthResponse response) {
        if (properties.isExposeAccessTokenInBody()) {
            return response;
        }
        return new AuthResponse(null, response.tokenType(), response.expiresAt(), response.currentUser());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(properties.getAccessTokenCookieName(), value)
                .httpOnly(true)
                .secure(properties.isAccessTokenCookieSecure())
                .sameSite(properties.getAccessTokenCookieSameSite())
                .path("/");
    }
}
