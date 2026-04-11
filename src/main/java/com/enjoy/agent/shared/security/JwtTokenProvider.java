package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 的签发与解析组件。
 */
@Component
public class JwtTokenProvider {

    private final Clock clock;
    private final SecurityProperties securityProperties;
    private final SecretKey secretKey;

    public JwtTokenProvider(Clock clock, SecurityProperties securityProperties) {
        this.clock = clock;
        this.securityProperties = securityProperties;
        this.secretKey = Keys.hmacShaKeyFor(securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为当前用户签发访问令牌。
     */
    public IssuedAccessToken issueToken(AuthenticatedUser authenticatedUser) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plusSeconds(securityProperties.getAccessTokenExpirationSeconds());

        String accessToken = Jwts.builder()
                .subject(authenticatedUser.userId().toString())
                .claim("email", authenticatedUser.email())
                .claim("displayName", authenticatedUser.displayName())
                .claim("tenantId", authenticatedUser.tenantId())
                .claim("tenantCode", authenticatedUser.tenantCode())
                .claim("tenantName", authenticatedUser.tenantName())
                .claim("role", authenticatedUser.role().name())
                .claim("systemRole", authenticatedUser.systemRole().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new IssuedAccessToken(accessToken, expiresAt);
    }

    /**
     * 解析 JWT，并提取系统真正关心的业务字段。
     */
    public JwtTokenClaims parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Number tenantId = claims.get("tenantId", Number.class);
        String role = claims.get("role", String.class);
        String systemRole = claims.get("systemRole", String.class);
        if (tenantId == null || claims.getSubject() == null || role == null || systemRole == null) {
            throw new IllegalArgumentException("Token is missing required claims");
        }

        return new JwtTokenClaims(
                Long.parseLong(claims.getSubject()),
                tenantId.longValue(),
                claims.get("email", String.class),
                claims.get("displayName", String.class),
                claims.get("tenantCode", String.class),
                claims.get("tenantName", String.class),
                TenantMemberRole.valueOf(role),
                SystemRole.valueOf(systemRole)
        );
    }
}
