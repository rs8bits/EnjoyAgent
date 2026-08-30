package com.enjoy.agent.shared.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

class JwtAuthenticationFilterTest {

    @Test
    void downstreamBusinessIllegalArgumentIsNotMisreportedAsAnInvalidJwt() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        AuthenticationSnapshotService snapshotService = mock(AuthenticationSnapshotService.class);
        AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);
        AccessTokenCookieService cookieService = mock(AccessTokenCookieService.class);
        JwtTokenClaims claims = new JwtTokenClaims(7L, 11L);
        AuthenticatedUser user = new AuthenticatedUser(
                7L, "user@example.com", "User", 11L, "tenant", "Tenant",
                TenantMemberRole.OWNER, SystemRole.USER
        );
        when(tokenProvider.parseToken("token")).thenReturn(claims);
        when(snapshotService.load(11L, 7L)).thenReturn(user);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                tokenProvider, snapshotService, entryPoint, cookieService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> {
            throw new IllegalArgumentException("business validation failed");
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("business validation failed");

        verify(entryPoint, never()).commence(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        org.assertj.core.api.Assertions.assertThat(MDC.get("tenantId")).isNull();
    }
}
