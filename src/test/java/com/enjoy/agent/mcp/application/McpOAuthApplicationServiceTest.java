package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.domain.enums.UserStatus;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.mcp.api.request.ConnectMcpOAuthClientCredentialsRequest;
import com.enjoy.agent.mcp.api.response.McpOAuthConnectionResponse;
import com.enjoy.agent.mcp.domain.entity.McpOAuthConnection;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpOAuthConnectionStatus;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.mcp.infrastructure.persistence.McpOAuthConnectionRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.crypto.CredentialProperties;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class McpOAuthApplicationServiceTest {

    @Mock
    private McpServerApplicationService mcpServerApplicationService;

    @Mock
    private McpOAuthConnectionRepository mcpOAuthConnectionRepository;

    @Mock
    private McpOAuthDiscoveryService mcpOAuthDiscoveryService;

    @Mock
    private AppUserRepository appUserRepository;

    private HttpServer server;
    private String baseUrl;
    private AesCryptoService aesCryptoService;

    @BeforeEach
    void setUp() throws Exception {
        CredentialProperties credentialProperties = new CredentialProperties();
        credentialProperties.setAesKey("0123456789abcdef0123456789abcdef");
        credentialProperties.validate();
        aesCryptoService = new AesCryptoService(credentialProperties);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                2L,
                "tester@example.com",
                "tester",
                1L,
                "tenant-1",
                "Tenant 1",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                authenticatedUser,
                "token",
                authenticatedUser.authorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void connectClientCredentials_exchangesTokenAndRuntimeCanReuseIt() throws Exception {
        AtomicReference<McpOAuthConnection> storedConnection = new AtomicReference<>();
        server.createContext("/auth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(body).contains("grant_type=client_credentials");
            assertThat(body).contains("client_id=client-id");
            assertThat(body).contains("client_secret=client-secret");
            assertThat(body).contains("resource=" + urlEncode(baseUrl + "/mcp"));
            json(exchange, 200, """
                    {
                      "access_token": "cc-access-token",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "scope": "mcp"
                    }
                    """);
        });

        McpServer serverEntity = serverEntity(McpAuthType.OAUTH_CLIENT_CREDENTIALS);
        AppUser user = user();
        when(mcpServerApplicationService.requireTenantOwnedServer(10L)).thenReturn(serverEntity);
        when(appUserRepository.findById(2L)).thenReturn(Optional.of(user));
        when(mcpOAuthDiscoveryService.discover(baseUrl + "/mcp")).thenReturn(new McpOAuthDiscoveryResult(
                baseUrl + "/.well-known/oauth-protected-resource/mcp",
                baseUrl + "/auth",
                baseUrl + "/auth/authorize",
                baseUrl + "/auth/token",
                baseUrl + "/mcp",
                true
        ));
        when(mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(10L, 1L)).thenAnswer(invocation ->
                Optional.ofNullable(storedConnection.get())
        );
        when(mcpOAuthConnectionRepository.save(any(McpOAuthConnection.class))).thenAnswer(invocation -> {
            McpOAuthConnection connection = invocation.getArgument(0);
            storedConnection.set(connection);
            return connection;
        });

        McpOAuthApplicationService service = new McpOAuthApplicationService(
                mcpServerApplicationService,
                mcpOAuthConnectionRepository,
                mcpOAuthDiscoveryService,
                oauthProperties(),
                appUserRepository,
                aesCryptoService
        );

        McpOAuthConnectionResponse response = service.connectClientCredentials(
                10L,
                new ConnectMcpOAuthClientCredentialsRequest("client-id", "client-secret", "mcp")
        );

        assertThat(response.status()).isEqualTo(McpOAuthConnectionStatus.CONNECTED.name());
        assertThat(response.connected()).isTrue();
        assertThat(response.clientId()).isEqualTo("client-id");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.grantedScopes()).isEqualTo("mcp");

        McpOAuthConnection stored = storedConnection.get();
        assertThat(stored).isNotNull();
        assertThat(stored.getStatus()).isEqualTo(McpOAuthConnectionStatus.CONNECTED);
        assertThat(aesCryptoService.decrypt(stored.getAccessTokenCiphertext())).isEqualTo("cc-access-token");

        String runtimeToken = service.resolveAccessTokenForRuntime(serverEntity);
        assertThat(runtimeToken).isEqualTo("cc-access-token");
    }

    @Test
    void resolveAccessTokenForRuntime_whenRefreshFails_clearsTokenStateAndMarksError() throws Exception {
        server.createContext("/auth/token", exchange -> json(
                exchange,
                401,
                """
                        {
                          "error": "invalid_grant",
                          "error_description": "refresh token expired"
                        }
                        """
        ));

        McpServer serverEntity = serverEntity(McpAuthType.OAUTH_AUTH_CODE);
        McpOAuthConnection connection = new McpOAuthConnection();
        connection.setTenant(serverEntity.getTenant());
        connection.setServer(serverEntity);
        connection.setConnectedByUser(user());
        connection.setStatus(McpOAuthConnectionStatus.CONNECTED);
        connection.setClientId("client-id");
        connection.setClientSecretCiphertext(aesCryptoService.encrypt("client-secret"));
        connection.setAccessTokenCiphertext(aesCryptoService.encrypt("stale-access-token"));
        connection.setRefreshTokenCiphertext(aesCryptoService.encrypt("refresh-token"));
        connection.setTokenEndpoint(baseUrl + "/auth/token");
        connection.setExpiresAt(Instant.now().minusSeconds(5));

        AtomicReference<McpOAuthConnection> storedConnection = new AtomicReference<>(connection);
        when(mcpOAuthConnectionRepository.findByServer_IdAndTenant_Id(10L, 1L)).thenAnswer(invocation ->
                Optional.ofNullable(storedConnection.get())
        );
        when(mcpOAuthConnectionRepository.save(any(McpOAuthConnection.class))).thenAnswer(invocation -> {
            McpOAuthConnection saved = invocation.getArgument(0);
            storedConnection.set(saved);
            return saved;
        });

        McpOAuthApplicationService service = new McpOAuthApplicationService(
                mcpServerApplicationService,
                mcpOAuthConnectionRepository,
                mcpOAuthDiscoveryService,
                oauthProperties(),
                appUserRepository,
                aesCryptoService
        );

        assertThatThrownBy(() -> service.resolveAccessTokenForRuntime(serverEntity))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("MCP_OAUTH_REAUTH_REQUIRED"));

        McpOAuthConnection saved = storedConnection.get();
        assertThat(saved.getStatus()).isEqualTo(McpOAuthConnectionStatus.ERROR);
        assertThat(saved.getAccessTokenCiphertext()).isNull();
        assertThat(saved.getRefreshTokenCiphertext()).isNull();
        assertThat(saved.getLastErrorCode()).isEqualTo("MCP_OAUTH_REAUTH_REQUIRED");
        assertThat(saved.getLastErrorMessage()).contains("refresh token expired");
    }

    private McpOAuthProperties oauthProperties() {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setPublicBaseUrl("http://127.0.0.1");
        properties.setCallbackPath("/api/mcp/oauth/callback");
        properties.setRefreshSkewSeconds(60);
        properties.setAuthorizationTtlSeconds(600);
        return properties;
    }

    private McpServer serverEntity(McpAuthType authType) {
        Tenant tenant = new Tenant();
        tenant.setId(1L);

        McpServer serverEntity = new McpServer();
        serverEntity.setId(10L);
        serverEntity.setTenant(tenant);
        serverEntity.setName("mock-server");
        serverEntity.setBaseUrl(baseUrl + "/mcp");
        serverEntity.setTransportType(McpTransportType.STREAMABLE_HTTP);
        serverEntity.setAuthType(authType);
        serverEntity.setEnabled(true);
        return serverEntity;
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setEmail("tester@example.com");
        user.setDisplayName("tester");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
