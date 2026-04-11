package com.enjoy.agent.shared.security;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户读取工具类。
 */
public final class CurrentUserContext {

    private CurrentUserContext() {
    }

    /**
     * 尝试从 SecurityContext 中读取当前用户。
     */
    public static Optional<AuthenticatedUser> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser authenticatedUser) {
            return Optional.of(authenticatedUser);
        }
        return Optional.empty();
    }

    /**
     * 强制获取当前用户，如果没有登录则直接抛出 401。
     */
    public static AuthenticatedUser requireCurrentUser() {
        return getCurrentUser()
                .orElseThrow(() -> new ApiException("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED));
    }
}
