package com.enjoy.agent.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = "a-strong-production-secret-with-more-than-32-bytes";
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void issuesAndValidatesIssuerAudienceAndKeyId() {
        JwtTokenProvider provider = new JwtTokenProvider(Clock.fixed(NOW, ZoneOffset.UTC), properties());
        AuthenticatedUser user = new AuthenticatedUser(
                7L, "user@example.com", "User", 11L, "tenant", "Tenant",
                TenantMemberRole.OWNER, SystemRole.USER
        );

        IssuedAccessToken issued = provider.issueToken(user);
        JwtTokenClaims claims = provider.parseToken(issued.accessToken());

        assertEquals(7L, claims.userId());
        assertEquals(11L, claims.tenantId());
    }

    @Test
    void rejectsTokenFromWrongIssuerEvenWithTheSameSigningKey() {
        String token = Jwts.builder()
                .header().keyId("primary").and()
                .issuer("another-service")
                .audience().add("enjoy-agent-web").and()
                .subject("7")
                .claim("tenantId", 11L)
                .claim("role", "OWNER")
                .claim("systemRole", "USER")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        JwtTokenProvider provider = new JwtTokenProvider(Clock.fixed(NOW, ZoneOffset.UTC), properties());
        assertThrows(RuntimeException.class, () -> provider.parseToken(token));
    }

    @Test
    void acceptsExplicitlyRetainedHistoricalSigningKey() {
        String oldSecret = "an-old-production-secret-with-more-than-32-bytes";
        String token = Jwts.builder()
                .header().keyId("old-2026-07").and()
                .issuer("enjoy-agent")
                .audience().add("enjoy-agent-web").and()
                .subject("7")
                .claim("tenantId", 11L)
                .claim("role", "OWNER")
                .claim("systemRole", "USER")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(oldSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        SecurityProperties properties = properties();
        properties.setKeyId("current-2026-08");
        properties.setPreviousJwtKeys(List.of("old-2026-07=" + oldSecret));

        JwtTokenClaims claims = new JwtTokenProvider(Clock.fixed(NOW, ZoneOffset.UTC), properties)
                .parseToken(token);

        assertEquals(7L, claims.userId());
    }

    private SecurityProperties properties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret(SECRET);
        properties.setIssuer("enjoy-agent");
        properties.setAudience("enjoy-agent-web");
        properties.setKeyId("primary");
        properties.setAccessTokenExpirationSeconds(600);
        return properties;
    }
}
