package com.enjoy.agent.mcp.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.mcp.domain.enums.McpOAuthConnectionStatus;
import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * MCP OAuth 连接实体。
 */
@Entity
@Table(
        name = "mcp_oauth_connection",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mcp_oauth_connection_server", columnNames = {"server_id"}),
                @UniqueConstraint(name = "uk_mcp_oauth_connection_state", columnNames = {"authorization_state"})
        }
)
public class McpOAuthConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServer server;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connected_by_user_id", nullable = false)
    private AppUser connectedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private McpOAuthConnectionStatus status;

    @Column(name = "resource_metadata_url", length = 1000)
    private String resourceMetadataUrl;

    @Column(name = "authorization_server_issuer", length = 1000)
    private String authorizationServerIssuer;

    @Column(name = "authorization_endpoint", length = 1000)
    private String authorizationEndpoint;

    @Column(name = "token_endpoint", length = 1000)
    private String tokenEndpoint;

    @Column(name = "resource_indicator", length = 1000)
    private String resourceIndicator;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_secret_ciphertext", columnDefinition = "TEXT")
    private String clientSecretCiphertext;

    @Column(name = "requested_scopes", length = 1000)
    private String requestedScopes;

    @Column(name = "granted_scopes", length = 1000)
    private String grantedScopes;

    @Column(name = "token_type", length = 64)
    private String tokenType;

    @Column(name = "access_token_ciphertext", columnDefinition = "TEXT")
    private String accessTokenCiphertext;

    @Column(name = "refresh_token_ciphertext", columnDefinition = "TEXT")
    private String refreshTokenCiphertext;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "authorization_state", length = 255)
    private String authorizationState;

    @Column(name = "authorization_expires_at")
    private Instant authorizationExpiresAt;

    @Column(name = "code_verifier_ciphertext", columnDefinition = "TEXT")
    private String codeVerifierCiphertext;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "last_authorized_at")
    private Instant lastAuthorizedAt;

    @Column(name = "last_token_refreshed_at")
    private Instant lastTokenRefreshedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public McpServer getServer() {
        return server;
    }

    public void setServer(McpServer server) {
        this.server = server;
    }

    public AppUser getConnectedByUser() {
        return connectedByUser;
    }

    public void setConnectedByUser(AppUser connectedByUser) {
        this.connectedByUser = connectedByUser;
    }

    public McpOAuthConnectionStatus getStatus() {
        return status;
    }

    public void setStatus(McpOAuthConnectionStatus status) {
        this.status = status;
    }

    public String getResourceMetadataUrl() {
        return resourceMetadataUrl;
    }

    public void setResourceMetadataUrl(String resourceMetadataUrl) {
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    public String getAuthorizationServerIssuer() {
        return authorizationServerIssuer;
    }

    public void setAuthorizationServerIssuer(String authorizationServerIssuer) {
        this.authorizationServerIssuer = authorizationServerIssuer;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public void setAuthorizationEndpoint(String authorizationEndpoint) {
        this.authorizationEndpoint = authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getResourceIndicator() {
        return resourceIndicator;
    }

    public void setResourceIndicator(String resourceIndicator) {
        this.resourceIndicator = resourceIndicator;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecretCiphertext() {
        return clientSecretCiphertext;
    }

    public void setClientSecretCiphertext(String clientSecretCiphertext) {
        this.clientSecretCiphertext = clientSecretCiphertext;
    }

    public String getRequestedScopes() {
        return requestedScopes;
    }

    public void setRequestedScopes(String requestedScopes) {
        this.requestedScopes = requestedScopes;
    }

    public String getGrantedScopes() {
        return grantedScopes;
    }

    public void setGrantedScopes(String grantedScopes) {
        this.grantedScopes = grantedScopes;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getAccessTokenCiphertext() {
        return accessTokenCiphertext;
    }

    public void setAccessTokenCiphertext(String accessTokenCiphertext) {
        this.accessTokenCiphertext = accessTokenCiphertext;
    }

    public String getRefreshTokenCiphertext() {
        return refreshTokenCiphertext;
    }

    public void setRefreshTokenCiphertext(String refreshTokenCiphertext) {
        this.refreshTokenCiphertext = refreshTokenCiphertext;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getAuthorizationState() {
        return authorizationState;
    }

    public void setAuthorizationState(String authorizationState) {
        this.authorizationState = authorizationState;
    }

    public Instant getAuthorizationExpiresAt() {
        return authorizationExpiresAt;
    }

    public void setAuthorizationExpiresAt(Instant authorizationExpiresAt) {
        this.authorizationExpiresAt = authorizationExpiresAt;
    }

    public String getCodeVerifierCiphertext() {
        return codeVerifierCiphertext;
    }

    public void setCodeVerifierCiphertext(String codeVerifierCiphertext) {
        this.codeVerifierCiphertext = codeVerifierCiphertext;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public Instant getLastAuthorizedAt() {
        return lastAuthorizedAt;
    }

    public void setLastAuthorizedAt(Instant lastAuthorizedAt) {
        this.lastAuthorizedAt = lastAuthorizedAt;
    }

    public Instant getLastTokenRefreshedAt() {
        return lastTokenRefreshedAt;
    }

    public void setLastTokenRefreshedAt(Instant lastTokenRefreshedAt) {
        this.lastTokenRefreshedAt = lastTokenRefreshedAt;
    }
}
