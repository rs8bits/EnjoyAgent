package com.enjoy.agent.shared.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enjoy.agent.auth.api.response.AuthResponse;
import com.enjoy.agent.auth.api.response.CurrentUserResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessTokenCookieServiceTest {

    @Test
    void writesSecureHttpOnlyCookieAndHidesBodyTokenInProductionMode() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAccessTokenCookieSecure(true);
        properties.setExposeAccessTokenInBody(false);
        AccessTokenCookieService service = new AccessTokenCookieService(
                properties,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
        );
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        AuthResponse auth = new AuthResponse(
                "signed-token",
                "Bearer",
                Instant.parse("2026-08-08T01:00:00Z"),
                new CurrentUserResponse(1L, "user@example.com", "User", 2L, "tenant", "Tenant", "OWNER", "USER")
        );

        service.write(servletResponse, auth.accessToken(), auth.expiresAt());

        String setCookie = servletResponse.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("enjoy_agent_access=signed-token"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertNull(service.browserSafeResponse(auth).accessToken());
    }
}
