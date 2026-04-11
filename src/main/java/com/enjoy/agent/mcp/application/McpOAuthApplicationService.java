package com.enjoy.agent.mcp.application;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.mcp.api.request.ConnectMcpOAuthClientCredentialsRequest;
import com.enjoy.agent.mcp.api.request.StartMcpOAuthAuthorizationRequest;
import com.enjoy.agent.mcp.api.response.McpOAuthAuthorizationResponse;
import com.enjoy.agent.mcp.api.response.McpOAuthConnectionResponse;
import com.enjoy.agent.mcp.domain.entity.McpOAuthConnection;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpOAuthConnectionStatus;
import com.enjoy.agent.mcp.infrastructure.persistence.McpOAuthConnectionRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MCP OAuth 授权与 token 生命周期管理。
 */
@Service
public class McpOAuthApplicationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final McpServerApplicationService mcpServerApplicationService;
    private final McpOAuthConnectionRepository mcpOAuthConnectionRepository;
    private final McpOAuthDiscoveryService mcpOAuthDiscoveryService;
    private final McpOAuthProperties mcpOAuthProperties;
    private final AppUserRepository appUserRepository;
    private final AesCryptoService aesCryptoService;
    private final RestClient restClient;

    public McpOAuthApplicationService(
            McpServerApplicationService mcpServerApplicationService,
            McpOAuthConnectionRepository mcpOAuthConnectionRepository,
            McpOAuthDiscoveryService mcpOAuthDiscoveryService,
            McpOAuthProperties mcpOAuthProperties,
            AppUserRepository appUserRepository,
            AesCryptoService aesCryptoService
    ) {
        this.mcpServerApplicationService = mcpServerApplicationService;
        this.mcpOAuthConnectionRepository = mcpOAuthConnectionRepository;
        this.mcpOAuthDiscoveryService = mcpOAuthDiscoveryService;
        this.mcpOAuthProperties = mcpOAuthProperties;
        this.appUserRepository = appUserRepository;
        this.aesCryptoService = aesCryptoService;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 发起 OAuth 授权并返回跳转 URL。
     */
    @Transactional
    public McpOAuthAuthorizationResponse startAuthorization(Long serverId, StartMcpOAuthAuthorizationRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        McpServer server = requireAuthCodeServer(serverId);
        McpOAuthDiscoveryResult discovery = mcpOAuthDiscoveryService.discover(server.getBaseUrl());
        if (!discovery.pkceS256Supported()) {
            throw new ApiException("MCP_OAUTH_PKCE_UNSUPPORTED", "Authorization server does not support PKCE S256", HttpStatus.BAD_GATEWAY);
        }

        AppUser connectedByUser = appUserRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));
        McpOAuthConnection connection = mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(serverId, currentUser.tenantId())
                .orElseGet(McpOAuthConnection::new);

        String state = randomToken(32);
        String codeVerifier = randomToken(48);
        Instant authorizationExpiresAt = Instant.now().plusSeconds(mcpOAuthProperties.getAuthorizationTtlSeconds());

        connection.setTenant(server.getTenant());
        connection.setServer(server);
        connection.setConnectedByUser(connectedByUser);
        connection.setStatus(McpOAuthConnectionStatus.PENDING);
        clearTokenState(connection);
        connection.setResourceMetadataUrl(discovery.resourceMetadataUrl());
        connection.setAuthorizationServerIssuer(discovery.authorizationServerIssuer());
        connection.setAuthorizationEndpoint(discovery.authorizationEndpoint());
        connection.setTokenEndpoint(discovery.tokenEndpoint());
        connection.setResourceIndicator(discovery.resourceIndicator());
        connection.setClientId(request.clientId().trim());
        connection.setClientSecretCiphertext(encryptNullable(normalizeNullable(request.clientSecret())));
        connection.setRequestedScopes(normalizeNullable(request.scope()));
        connection.setAuthorizationState(state);
        connection.setAuthorizationExpiresAt(authorizationExpiresAt);
        connection.setCodeVerifierCiphertext(aesCryptoService.encrypt(codeVerifier));
        connection.setLastErrorCode(null);
        connection.setLastErrorMessage(null);
        mcpOAuthConnectionRepository.save(connection);

        String authorizationUrl = UriComponentsBuilder.fromUriString(discovery.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", connection.getClientId())
                .queryParam("redirect_uri", callbackUrl())
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge(codeVerifier))
                .queryParam("code_challenge_method", "S256")
                .queryParamIfPresent("scope", java.util.Optional.ofNullable(connection.getRequestedScopes()))
                .queryParamIfPresent("resource", java.util.Optional.ofNullable(connection.getResourceIndicator()))
                .build()
                .encode()
                .toUriString();

        return new McpOAuthAuthorizationResponse(
                connection.getStatus().name(),
                authorizationUrl,
                connection.getResourceMetadataUrl(),
                connection.getAuthorizationServerIssuer(),
                authorizationExpiresAt
        );
    }

    /**
     * 使用 client credentials 立即建立 OAuth 连接。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public McpOAuthConnectionResponse connectClientCredentials(
            Long serverId,
            ConnectMcpOAuthClientCredentialsRequest request
    ) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        McpServer server = requireClientCredentialsServer(serverId);
        AppUser connectedByUser = appUserRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));
        McpOAuthDiscoveryResult discovery = mcpOAuthDiscoveryService.discover(server.getBaseUrl());

        McpOAuthConnection connection = mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(serverId, currentUser.tenantId())
                .orElseGet(McpOAuthConnection::new);
        connection.setTenant(server.getTenant());
        connection.setServer(server);
        connection.setConnectedByUser(connectedByUser);
        connection.setStatus(McpOAuthConnectionStatus.PENDING);
        clearTokenState(connection);
        connection.setResourceMetadataUrl(discovery.resourceMetadataUrl());
        connection.setAuthorizationServerIssuer(discovery.authorizationServerIssuer());
        connection.setAuthorizationEndpoint(discovery.authorizationEndpoint());
        connection.setTokenEndpoint(discovery.tokenEndpoint());
        connection.setResourceIndicator(discovery.resourceIndicator());
        connection.setClientId(request.clientId().trim());
        connection.setClientSecretCiphertext(aesCryptoService.encrypt(request.clientSecret().trim()));
        connection.setRequestedScopes(normalizeNullable(request.scope()));
        connection.setAuthorizationState(null);
        connection.setAuthorizationExpiresAt(null);
        connection.setCodeVerifierCiphertext(null);
        connection.setLastErrorCode(null);
        connection.setLastErrorMessage(null);

        McpOAuthTokenResult tokenResult = exchangeClientCredentials(connection);
        applyTokenResult(connection, tokenResult, false);
        connection.setStatus(McpOAuthConnectionStatus.CONNECTED);
        connection.setLastAuthorizedAt(Instant.now());
        mcpOAuthConnectionRepository.save(connection);
        return toResponse(connection);
    }

    /**
     * 查询某个 MCP Server 的 OAuth 连接状态。
     */
    @Transactional(readOnly = true)
    public McpOAuthConnectionResponse getConnection(Long serverId) {
        McpServer server = mcpServerApplicationService.requireTenantOwnedServer(serverId);
        return mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(serverId, server.getTenant().getId())
                .map(this::toResponse)
                .orElseGet(() -> new McpOAuthConnectionResponse(
                        serverId,
                        McpOAuthConnectionStatus.NOT_CONNECTED.name(),
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
    }

    /**
     * 断开 OAuth 连接。
     */
    @Transactional
    public void disconnect(Long serverId) {
        mcpServerApplicationService.requireTenantOwnedServer(serverId);
        mcpOAuthConnectionRepository.deleteByServer_Id(serverId);
    }

    /**
     * OAuth callback：用 code 换 token 并落库。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void handleCallback(String state, String code, String error, String errorDescription) {
        if (!StringUtils.hasText(state)) {
            throw new ApiException("MCP_OAUTH_CALLBACK_INVALID", "Missing OAuth state", HttpStatus.BAD_REQUEST);
        }
        McpOAuthConnection connection = mcpOAuthConnectionRepository.findByAuthorizationState(state)
                .orElseThrow(() -> new ApiException("MCP_OAUTH_CALLBACK_INVALID", "OAuth state is invalid or expired", HttpStatus.BAD_REQUEST));
        if (connection.getAuthorizationExpiresAt() != null && connection.getAuthorizationExpiresAt().isBefore(Instant.now())) {
            markError(connection, "MCP_OAUTH_STATE_EXPIRED", "OAuth state has expired");
            throw new ApiException("MCP_OAUTH_CALLBACK_INVALID", "OAuth state has expired", HttpStatus.BAD_REQUEST);
        }
        if (StringUtils.hasText(error)) {
            markError(connection, "MCP_OAUTH_AUTHORIZATION_DENIED", buildAuthorizationErrorMessage(error, errorDescription));
            throw new ApiException("MCP_OAUTH_AUTHORIZATION_DENIED", buildAuthorizationErrorMessage(error, errorDescription), HttpStatus.BAD_REQUEST);
        }
        if (!StringUtils.hasText(code)) {
            markError(connection, "MCP_OAUTH_CALLBACK_INVALID", "OAuth callback did not contain authorization code");
            throw new ApiException("MCP_OAUTH_CALLBACK_INVALID", "OAuth callback did not contain authorization code", HttpStatus.BAD_REQUEST);
        }

        McpOAuthTokenResult tokenResult = exchangeAuthorizationCode(connection, code);
        applyTokenResult(connection, tokenResult, false);
        clearPendingAuthorization(connection);
        connection.setStatus(McpOAuthConnectionStatus.CONNECTED);
        connection.setLastErrorCode(null);
        connection.setLastErrorMessage(null);
        connection.setLastAuthorizedAt(Instant.now());
        mcpOAuthConnectionRepository.save(connection);
    }

    /**
     * 为运行时准备 OAuth access token；必要时自动 refresh。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public String resolveAccessTokenForRuntime(McpServer server) {
        McpOAuthConnection connection = mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(server.getId(), server.getTenant().getId())
                .orElseThrow(() -> new ApiException(
                        "MCP_OAUTH_CONNECTION_REQUIRED",
                        "OAuth connection is required before invoking this MCP server",
                        HttpStatus.BAD_REQUEST
                ));

        if (!StringUtils.hasText(connection.getAccessTokenCiphertext())) {
            throw new ApiException(
                    "MCP_OAUTH_CONNECTION_REQUIRED",
                    "OAuth connection is not completed yet",
                    HttpStatus.BAD_REQUEST
            );
        }

        Instant now = Instant.now();
        Instant refreshDeadline = now.plusSeconds(mcpOAuthProperties.getRefreshSkewSeconds());
        if (connection.getExpiresAt() != null && !connection.getExpiresAt().isAfter(refreshDeadline)) {
            if (server.getAuthType() == McpAuthType.OAUTH_CLIENT_CREDENTIALS) {
                McpOAuthTokenResult refreshed = exchangeClientCredentials(connection);
                applyTokenResult(connection, refreshed, true);
                mcpOAuthConnectionRepository.save(connection);
            } else {
                if (!StringUtils.hasText(connection.getRefreshTokenCiphertext())) {
                    throw new ApiException(
                            "MCP_OAUTH_REAUTH_REQUIRED",
                            "OAuth access token expired and refresh_token is unavailable",
                            HttpStatus.BAD_REQUEST
                    );
                }
                McpOAuthTokenResult refreshed = refreshAccessToken(connection);
                applyTokenResult(connection, refreshed, true);
                mcpOAuthConnectionRepository.save(connection);
            }
        }

        return aesCryptoService.decrypt(connection.getAccessTokenCiphertext());
    }

    private McpOAuthTokenResult exchangeAuthorizationCode(McpOAuthConnection connection, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", callbackUrl());
        form.add("client_id", connection.getClientId());
        form.add("code_verifier", aesCryptoService.decrypt(connection.getCodeVerifierCiphertext()));
        if (StringUtils.hasText(connection.getClientSecretCiphertext())) {
            form.add("client_secret", aesCryptoService.decrypt(connection.getClientSecretCiphertext()));
        }
        if (StringUtils.hasText(connection.getResourceIndicator())) {
            form.add("resource", connection.getResourceIndicator());
        }
        return invokeTokenEndpoint(connection, form, "MCP_OAUTH_TOKEN_EXCHANGE_FAILED");
    }

    private McpOAuthTokenResult exchangeClientCredentials(McpOAuthConnection connection) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", connection.getClientId());
            form.add("client_secret", aesCryptoService.decrypt(connection.getClientSecretCiphertext()));
            if (StringUtils.hasText(connection.getRequestedScopes())) {
                form.add("scope", connection.getRequestedScopes());
            }
            if (StringUtils.hasText(connection.getResourceIndicator())) {
                form.add("resource", connection.getResourceIndicator());
            }
            return invokeTokenEndpoint(connection, form, "MCP_OAUTH_TOKEN_EXCHANGE_FAILED");
        } catch (ApiException ex) {
            markError(connection, ex.getCode(), ex.getMessage());
            throw ex;
        }
    }

    private McpOAuthTokenResult refreshAccessToken(McpOAuthConnection connection) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", aesCryptoService.decrypt(connection.getRefreshTokenCiphertext()));
        form.add("client_id", connection.getClientId());
        if (StringUtils.hasText(connection.getClientSecretCiphertext())) {
            form.add("client_secret", aesCryptoService.decrypt(connection.getClientSecretCiphertext()));
        }
        if (StringUtils.hasText(connection.getResourceIndicator())) {
            form.add("resource", connection.getResourceIndicator());
        }
        try {
            return invokeTokenEndpoint(connection, form, "MCP_OAUTH_REFRESH_FAILED");
        } catch (ApiException ex) {
            markError(connection, "MCP_OAUTH_REAUTH_REQUIRED", ex.getMessage());
            throw new ApiException("MCP_OAUTH_REAUTH_REQUIRED", "OAuth refresh failed, re-authorization is required", HttpStatus.BAD_REQUEST);
        }
    }

    private McpOAuthTokenResult invokeTokenEndpoint(
            McpOAuthConnection connection,
            MultiValueMap<String, String> form,
            String errorCode
    ) {
        TokenHttpResponse response = restClient.post()
                .uri(URI.create(connection.getTokenEndpoint()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .exchange((request, clientResponse) -> new TokenHttpResponse(
                        clientResponse.getStatusCode().value(),
                        clientResponse.getHeaders(),
                        clientResponse.bodyTo(String.class)
                ));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "OAuth token endpoint returned HTTP " + response.statusCode();
            String responseDetail = extractTokenEndpointError(response.body());
            if (StringUtils.hasText(responseDetail)) {
                message += ": " + responseDetail;
            }
            markError(connection, errorCode, message, true);
            throw new ApiException(errorCode, message, HttpStatus.BAD_GATEWAY);
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(response.body());
            String accessToken = textOrNull(json.get("access_token"));
            if (!StringUtils.hasText(accessToken)) {
                markError(connection, errorCode, "OAuth token endpoint did not return access_token", true);
                throw new ApiException(errorCode, "OAuth token endpoint did not return access_token", HttpStatus.BAD_GATEWAY);
            }
            String refreshToken = textOrNull(json.get("refresh_token"));
            String tokenType = textOrNull(json.get("token_type"));
            Long expiresIn = json.hasNonNull("expires_in") ? json.get("expires_in").asLong() : null;
            Instant expiresAt = expiresIn == null ? null : Instant.now().plusSeconds(expiresIn);
            String scope = textOrNull(json.get("scope"));
            return new McpOAuthTokenResult(accessToken, refreshToken, tokenType, expiresAt, scope);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            markError(connection, errorCode, "Failed to parse OAuth token response", true);
            throw new ApiException(errorCode, "Failed to parse OAuth token response", HttpStatus.BAD_GATEWAY);
        }
    }

    private void applyTokenResult(McpOAuthConnection connection, McpOAuthTokenResult tokenResult, boolean refreshed) {
        connection.setAccessTokenCiphertext(aesCryptoService.encrypt(tokenResult.accessToken()));
        if (StringUtils.hasText(tokenResult.refreshToken())) {
            connection.setRefreshTokenCiphertext(aesCryptoService.encrypt(tokenResult.refreshToken()));
        } else if (refreshed || !StringUtils.hasText(connection.getRefreshTokenCiphertext())) {
            connection.setRefreshTokenCiphertext(null);
        }
        connection.setTokenType(StringUtils.hasText(tokenResult.tokenType()) ? tokenResult.tokenType() : "Bearer");
        connection.setExpiresAt(tokenResult.expiresAt());
        if (StringUtils.hasText(tokenResult.grantedScopes())) {
            connection.setGrantedScopes(tokenResult.grantedScopes());
        }
        if (refreshed) {
            connection.setLastTokenRefreshedAt(Instant.now());
        }
        connection.setStatus(McpOAuthConnectionStatus.CONNECTED);
        connection.setLastErrorCode(null);
        connection.setLastErrorMessage(null);
    }

    private void clearPendingAuthorization(McpOAuthConnection connection) {
        connection.setAuthorizationState(null);
        connection.setAuthorizationExpiresAt(null);
        connection.setCodeVerifierCiphertext(null);
    }

    private void clearTokenState(McpOAuthConnection connection) {
        connection.setAccessTokenCiphertext(null);
        connection.setRefreshTokenCiphertext(null);
        connection.setExpiresAt(null);
        connection.setGrantedScopes(null);
        connection.setTokenType(null);
    }

    private void markError(McpOAuthConnection connection, String code, String message) {
        markError(connection, code, message, false);
    }

    private void markError(McpOAuthConnection connection, String code, String message, boolean clearTokens) {
        connection.setStatus(McpOAuthConnectionStatus.ERROR);
        connection.setLastErrorCode(code);
        connection.setLastErrorMessage(truncate(message, 1000));
        if (clearTokens) {
            clearTokenState(connection);
        }
        clearPendingAuthorization(connection);
        mcpOAuthConnectionRepository.save(connection);
    }

    private McpOAuthConnectionResponse toResponse(McpOAuthConnection connection) {
        return new McpOAuthConnectionResponse(
                connection.getServer().getId(),
                connection.getStatus().name(),
                StringUtils.hasText(connection.getAccessTokenCiphertext()),
                connection.getResourceMetadataUrl(),
                connection.getAuthorizationServerIssuer(),
                connection.getAuthorizationEndpoint(),
                connection.getTokenEndpoint(),
                connection.getClientId(),
                connection.getRequestedScopes(),
                connection.getGrantedScopes(),
                connection.getTokenType(),
                connection.getExpiresAt(),
                connection.getLastAuthorizedAt(),
                connection.getLastTokenRefreshedAt(),
                connection.getLastErrorCode(),
                connection.getLastErrorMessage()
        );
    }

    private McpServer requireAuthCodeServer(Long serverId) {
        McpServer server = mcpServerApplicationService.requireTenantOwnedServer(serverId);
        if (server.getAuthType() != McpAuthType.OAUTH_AUTH_CODE) {
            throw new ApiException("MCP_SERVER_AUTH_INVALID", "Current MCP server is not configured for OAuth Auth Code", HttpStatus.BAD_REQUEST);
        }
        return server;
    }

    private McpServer requireClientCredentialsServer(Long serverId) {
        McpServer server = mcpServerApplicationService.requireTenantOwnedServer(serverId);
        if (server.getAuthType() != McpAuthType.OAUTH_CLIENT_CREDENTIALS) {
            throw new ApiException("MCP_SERVER_AUTH_INVALID", "Current MCP server is not configured for OAuth client credentials", HttpStatus.BAD_REQUEST);
        }
        return server;
    }

    private String callbackUrl() {
        return UriComponentsBuilder.fromUriString(mcpOAuthProperties.getPublicBaseUrl())
                .path(mcpOAuthProperties.getCallbackPath())
                .build()
                .toUriString();
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String encryptNullable(String value) {
        return StringUtils.hasText(value) ? aesCryptoService.encrypt(value) : null;
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        SECURE_RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate PKCE challenge", ex);
        }
    }

    private String buildAuthorizationErrorMessage(String error, String errorDescription) {
        if (StringUtils.hasText(errorDescription)) {
            return error + ": " + errorDescription;
        }
        return error;
    }

    private String textOrNull(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String extractTokenEndpointError(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(body);
            String error = textOrNull(json.get("error"));
            String description = textOrNull(json.get("error_description"));
            if (StringUtils.hasText(error) && StringUtils.hasText(description)) {
                return error + ": " + description;
            }
            if (StringUtils.hasText(error)) {
                return error;
            }
            if (StringUtils.hasText(description)) {
                return description;
            }
        } catch (Exception ignored) {
            // Fall back to raw body snippet below.
        }
        return truncate(body.trim(), 300);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record TokenHttpResponse(
            int statusCode,
            HttpHeaders headers,
            String body
    ) {
    }
}
