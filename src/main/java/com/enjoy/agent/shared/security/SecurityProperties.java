package com.enjoy.agent.shared.security;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关配置。
 */
@ConfigurationProperties(prefix = "enjoy.security")
public class SecurityProperties {

    private static final String INSECURE_DEVELOPMENT_SECRET = "change-me-to-a-long-random-string";
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern COOKIE_NAME_PATTERN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final long MAX_ACCESS_TOKEN_EXPIRATION_SECONDS = 86_400;

    private String jwtSecret;
    private boolean allowInsecureDevelopmentSecret;
    private String issuer = "enjoy-agent";
    private String audience = "enjoy-agent-web";
    private String keyId = "primary";
    private List<String> previousJwtKeys = new ArrayList<>();
    private String accessTokenCookieName = "enjoy_agent_access";
    private boolean accessTokenCookieSecure = true;
    private String accessTokenCookieSameSite = "Strict";
    private boolean exposeAccessTokenInBody;
    private boolean allowPublicDeveloperEndpoints;
    private List<String> allowedBrowserOrigins = new ArrayList<>();
    private long accessTokenExpirationSeconds = 7200;

    /**
     * 启动时校验 JWT 密钥长度，避免弱密钥导致运行时报错。
     */
    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("enjoy.security.jwt-secret must be at least 32 bytes long");
        }
        if (!allowInsecureDevelopmentSecret && INSECURE_DEVELOPMENT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("enjoy.security.jwt-secret must not use the development default");
        }
        if (accessTokenExpirationSeconds <= 0
                || accessTokenExpirationSeconds > MAX_ACCESS_TOKEN_EXPIRATION_SECONDS) {
            throw new IllegalStateException(
                    "enjoy.security.access-token-expiration-seconds must be between 1 and 86400");
        }
        if (isBlank(issuer) || isBlank(audience) || isBlank(keyId) || isBlank(accessTokenCookieName)) {
            throw new IllegalStateException("enjoy.security issuer, audience, key-id and cookie name must not be blank");
        }
        if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalStateException("enjoy.security.key-id contains unsupported characters");
        }
        if (!COOKIE_NAME_PATTERN.matcher(accessTokenCookieName).matches()) {
            throw new IllegalStateException("enjoy.security access-token cookie name is invalid");
        }
        verificationSecretsByKeyId();
        if (!("Strict".equalsIgnoreCase(accessTokenCookieSameSite)
                || "Lax".equalsIgnoreCase(accessTokenCookieSameSite))) {
            throw new IllegalStateException("enjoy.security.access-token-cookie-same-site must be Strict or Lax");
        }
        if (!allowInsecureDevelopmentSecret
                && (!accessTokenCookieSecure || exposeAccessTokenInBody || allowPublicDeveloperEndpoints)) {
            throw new IllegalStateException(
                    "Insecure browser/developer security switches require allow-insecure-development-secret=true");
        }
        for (String origin : allowedBrowserOrigins) {
            validateBrowserOrigin(origin);
        }
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public boolean isAllowInsecureDevelopmentSecret() {
        return allowInsecureDevelopmentSecret;
    }

    public void setAllowInsecureDevelopmentSecret(boolean allowInsecureDevelopmentSecret) {
        this.allowInsecureDevelopmentSecret = allowInsecureDevelopmentSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public List<String> getPreviousJwtKeys() {
        return List.copyOf(previousJwtKeys);
    }

    public void setPreviousJwtKeys(List<String> previousJwtKeys) {
        this.previousJwtKeys = previousJwtKeys == null
                ? new ArrayList<>()
                : previousJwtKeys.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /** Returns current and explicitly retained historical signing secrets keyed by JWT kid. */
    public Map<String, String> verificationSecretsByKeyId() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(keyId, jwtSecret);
        for (String entry : previousJwtKeys) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                        "enjoy.security.previous-jwt-keys entries must use kid=secret");
            }
            String previousKeyId = entry.substring(0, separator).trim();
            String previousSecret = entry.substring(separator + 1).trim();
            if (!KEY_ID_PATTERN.matcher(previousKeyId).matches()) {
                throw new IllegalStateException("Historical JWT key id contains unsupported characters");
            }
            if (previousSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("Historical JWT secrets must be at least 32 bytes long");
            }
            if (!allowInsecureDevelopmentSecret && INSECURE_DEVELOPMENT_SECRET.equals(previousSecret)) {
                throw new IllegalStateException("Historical JWT secrets must not use the development default");
            }
            if (keys.putIfAbsent(previousKeyId, previousSecret) != null) {
                throw new IllegalStateException("JWT key ids must be unique");
            }
        }
        return Map.copyOf(keys);
    }

    public String getAccessTokenCookieName() {
        return accessTokenCookieName;
    }

    public void setAccessTokenCookieName(String accessTokenCookieName) {
        this.accessTokenCookieName = accessTokenCookieName;
    }

    public boolean isAccessTokenCookieSecure() {
        return accessTokenCookieSecure;
    }

    public void setAccessTokenCookieSecure(boolean accessTokenCookieSecure) {
        this.accessTokenCookieSecure = accessTokenCookieSecure;
    }

    public String getAccessTokenCookieSameSite() {
        return accessTokenCookieSameSite;
    }

    public void setAccessTokenCookieSameSite(String accessTokenCookieSameSite) {
        this.accessTokenCookieSameSite = accessTokenCookieSameSite;
    }

    public boolean isExposeAccessTokenInBody() {
        return exposeAccessTokenInBody;
    }

    public void setExposeAccessTokenInBody(boolean exposeAccessTokenInBody) {
        this.exposeAccessTokenInBody = exposeAccessTokenInBody;
    }

    public boolean isAllowPublicDeveloperEndpoints() {
        return allowPublicDeveloperEndpoints;
    }

    public void setAllowPublicDeveloperEndpoints(boolean allowPublicDeveloperEndpoints) {
        this.allowPublicDeveloperEndpoints = allowPublicDeveloperEndpoints;
    }

    public List<String> getAllowedBrowserOrigins() {
        return allowedBrowserOrigins;
    }

    public void setAllowedBrowserOrigins(List<String> allowedBrowserOrigins) {
        this.allowedBrowserOrigins = allowedBrowserOrigins == null
                ? new ArrayList<>()
                : allowedBrowserOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public void setAccessTokenExpirationSeconds(long accessTokenExpirationSeconds) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validateBrowserOrigin(String value) {
        try {
            URI origin = URI.create(value == null ? "" : value.trim());
            boolean validScheme = "http".equalsIgnoreCase(origin.getScheme())
                    || "https".equalsIgnoreCase(origin.getScheme());
            if (!validScheme
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getPath() == null
                    || !origin.getPath().isEmpty()
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || value.contains("*")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "enjoy.security.allowed-browser-origins entries must be exact HTTP(S) origins");
        }
    }
}
