package com.enjoy.agent.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final Map<String, SecretKey> verificationKeys;

    public JwtTokenProvider(Clock clock, SecurityProperties securityProperties) {
        this.clock = clock;
        this.securityProperties = securityProperties;
        this.secretKey = Keys.hmacShaKeyFor(securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.verificationKeys = securityProperties.verificationSecretsByKeyId().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Keys.hmacShaKeyFor(entry.getValue().getBytes(StandardCharsets.UTF_8))
                ));
    }

    /**
     * 为当前用户签发访问令牌。
     */
    public IssuedAccessToken issueToken(AuthenticatedUser authenticatedUser) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plusSeconds(securityProperties.getAccessTokenExpirationSeconds());

        String accessToken = Jwts.builder()
                .header()
                .keyId(securityProperties.getKeyId())
                .and()
                .issuer(securityProperties.getIssuer())
                .audience()
                .add(securityProperties.getAudience())
                .and()
                .subject(authenticatedUser.userId().toString())
                .claim("tenantId", authenticatedUser.tenantId())
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
        Jws<Claims> parsedToken = Jwts.parser()
                .keyLocator(header -> {
                    Object keyId = header.get("kid");
                    SecretKey verificationKey = keyId == null
                            ? null
                            : verificationKeys.get(keyId.toString());
                    if (verificationKey == null) {
                        throw new UnsupportedJwtException("Token key id is not accepted");
                    }
                    return verificationKey;
                })
                .clock(() -> Date.from(clock.instant()))
                .requireIssuer(securityProperties.getIssuer())
                .requireAudience(securityProperties.getAudience())
                .build()
                .parseSignedClaims(token);
        Claims claims = parsedToken.getPayload();

        Number tenantId = claims.get("tenantId", Number.class);
        if (tenantId == null || claims.getSubject() == null) {
            throw new IllegalArgumentException("Token is missing required claims");
        }

        return new JwtTokenClaims(
                Long.parseLong(claims.getSubject()),
                tenantId.longValue()
        );
    }
}
