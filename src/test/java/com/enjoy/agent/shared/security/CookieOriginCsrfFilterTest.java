package com.enjoy.agent.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieOriginCsrfFilterTest {

    @Test
    void allowsSameOriginCookieAuthenticatedWrite() throws Exception {
        SecurityProperties properties = new SecurityProperties();
        CookieOriginCsrfFilter filter = new CookieOriginCsrfFilter(
                properties,
                (request, response, exception) -> response.sendError(403)
        );
        MockHttpServletRequest request = request("http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertEquals(true, invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsUnapprovedCrossOriginCookieAuthenticatedWrite() throws Exception {
        SecurityProperties properties = new SecurityProperties();
        CookieOriginCsrfFilter filter = new CookieOriginCsrfFilter(
                properties,
                (request, response, exception) -> response.sendError(403)
        );
        MockHttpServletRequest request = request("https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    private MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agents");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addHeader("Origin", origin);
        request.setAttribute(JwtAuthenticationFilter.COOKIE_AUTHENTICATION_ATTRIBUTE, true);
        return request;
    }
}
