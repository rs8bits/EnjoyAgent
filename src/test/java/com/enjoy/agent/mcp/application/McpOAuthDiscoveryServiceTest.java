package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpOAuthDiscoveryServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discover_readsProtectedResourceAndAuthorizationMetadataFromWellKnownPaths() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/oauth-protected-resource/mcp", exchange -> json(
                exchange,
                200,
                """
                        {
                          "resource": "%s/mcp",
                          "authorization_servers": ["%s/auth"]
                        }
                        """.formatted(baseUrl, baseUrl)
        ));
        server.createContext("/.well-known/oauth-authorization-server/auth", exchange -> json(
                exchange,
                200,
                """
                        {
                          "authorization_endpoint": "%s/auth/authorize",
                          "token_endpoint": "%s/auth/token",
                          "code_challenge_methods_supported": ["S256"]
                        }
                        """.formatted(baseUrl, baseUrl)
        ));
        server.start();

        McpOAuthDiscoveryService service = new McpOAuthDiscoveryService(new ObjectMapper());
        McpOAuthDiscoveryResult result = service.discover(baseUrl + "/mcp");

        assertThat(result.resourceMetadataUrl()).isEqualTo(baseUrl + "/.well-known/oauth-protected-resource/mcp");
        assertThat(result.authorizationServerIssuer()).isEqualTo(baseUrl + "/auth");
        assertThat(result.authorizationEndpoint()).isEqualTo(baseUrl + "/auth/authorize");
        assertThat(result.tokenEndpoint()).isEqualTo(baseUrl + "/auth/token");
        assertThat(result.resourceIndicator()).isEqualTo(baseUrl + "/mcp");
        assertThat(result.pkceS256Supported()).isTrue();
    }

    @Test
    void discover_fallsBackToUnauthorizedChallengeResourceMetadata() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"" + baseUrl + "/metadata/protected\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/metadata/protected", exchange -> json(
                exchange,
                200,
                """
                        {
                          "resource": "%s/mcp",
                          "authorization_servers": ["%s/issuer"]
                        }
                        """.formatted(baseUrl, baseUrl)
        ));
        server.createContext("/.well-known/oauth-authorization-server/issuer", exchange -> json(
                exchange,
                200,
                """
                        {
                          "authorization_endpoint": "%s/issuer/authorize",
                          "token_endpoint": "%s/issuer/token"
                        }
                        """.formatted(baseUrl, baseUrl)
        ));
        server.start();

        McpOAuthDiscoveryService service = new McpOAuthDiscoveryService(new ObjectMapper());
        McpOAuthDiscoveryResult result = service.discover(baseUrl + "/mcp");

        assertThat(result.resourceMetadataUrl()).isEqualTo(baseUrl + "/metadata/protected");
        assertThat(result.authorizationServerIssuer()).isEqualTo(baseUrl + "/issuer");
        assertThat(result.authorizationEndpoint()).isEqualTo(baseUrl + "/issuer/authorize");
        assertThat(result.tokenEndpoint()).isEqualTo(baseUrl + "/issuer/token");
        assertThat(result.resourceIndicator()).isEqualTo(baseUrl + "/mcp");
        assertThat(result.pkceS256Supported()).isTrue();
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
