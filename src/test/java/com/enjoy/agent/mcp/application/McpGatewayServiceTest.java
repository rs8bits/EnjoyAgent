package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.enjoy.agent.shared.security.OutboundUrlProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpGatewayServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listToolsAndCallTool_sendBearerTokenAndSessionHeader() throws Exception {
        AtomicInteger initializeCount = new AtomicInteger();
        AtomicInteger initializedNotificationCount = new AtomicInteger();
        AtomicReference<String> lastAuthorizationHeader = new AtomicReference<>();
        AtomicReference<String> lastSessionHeader = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", exchange -> {
            lastAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastSessionHeader.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            JsonNode request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();

            switch (method) {
                case "initialize" -> {
                    initializeCount.incrementAndGet();
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                    json(exchange, 200, """
                            {
                              "jsonrpc": "2.0",
                              "id": %s,
                              "result": {
                                "protocolVersion": "2025-03-26",
                                "capabilities": {
                                  "tools": {"listChanged": false}
                                },
                                "serverInfo": {
                                  "name": "mock",
                                  "version": "1.0.0"
                                }
                              }
                            }
                            """.formatted(request.path("id")));
                }
                case "notifications/initialized" -> {
                    initializedNotificationCount.incrementAndGet();
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                }
                case "tools/list" -> json(exchange, 200, """
                        {
                          "jsonrpc": "2.0",
                          "id": %s,
                          "result": {
                            "tools": [
                              {
                                "name": "demo_tool",
                                "description": "demo",
                                "inputSchema": {
                                  "type": "object",
                                  "properties": {
                                    "foo": {"type": "string"}
                                  }
                                }
                              }
                            ]
                          }
                        }
                        """.formatted(request.path("id")));
                case "tools/call" -> sse(exchange, 200, """
                        event: message
                        data: {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"tool-ok"}],"structuredContent":{"ok":true}}}

                        """.formatted(request.path("id")));
                default -> json(exchange, 400, """
                        {
                          "jsonrpc": "2.0",
                          "id": %s,
                          "error": {
                            "message": "unexpected method"
                          }
                        }
                        """.formatted(request.path("id")));
            }
        });
        server.start();

        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setHttpConnectTimeoutSeconds(2);
        properties.setHttpReadTimeoutSeconds(2);
        McpGatewayService gatewayService = new McpGatewayService(objectMapper, properties, allowPrivateOutboundPolicy());
        PreparedMcpServer preparedServer = new PreparedMcpServer(
                1L,
                "mock-server",
                baseUrl,
                McpTransportType.STREAMABLE_HTTP,
                "test-token"
        );

        assertThat(gatewayService.listTools(preparedServer))
                .singleElement()
                .satisfies(tool -> assertThat(tool.name()).isEqualTo("demo_tool"));

        PreparedMcpTool tool = new PreparedMcpTool(
                2L,
                "demo_tool",
                "demo",
                "{\"type\":\"object\"}",
                McpToolRiskLevel.LOW,
                preparedServer
        );
        McpToolCallResult result = gatewayService.callTool(tool, Map.of("foo", "bar"));

        assertThat(result.modelVisibleContent()).isEqualTo("tool-ok");
        assertThat(result.rawResponsePayload()).contains("\"structuredContent\"");
        assertThat(lastAuthorizationHeader.get()).isEqualTo("Bearer test-token");
        assertThat(lastSessionHeader.get()).isEqualTo("session-1");
        assertThat(initializeCount.get()).isEqualTo(2);
        assertThat(initializedNotificationCount.get()).isEqualTo(2);
    }

    @Test
    void listTools_rejectsUnsupportedSseTransport() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        McpGatewayService gatewayService = new McpGatewayService(objectMapper, properties, allowPrivateOutboundPolicy());
        PreparedMcpServer preparedServer = new PreparedMcpServer(
                1L,
                "legacy-sse",
                "http://127.0.0.1:1/sse",
                McpTransportType.SSE,
                null
        );

        assertThatThrownBy(() -> gatewayService.listTools(preparedServer))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("MCP_TRANSPORT_NOT_SUPPORTED"));
    }

    @Test
    void listTools_paginatesAndCallToolPreservesProtocolError() throws Exception {
        AtomicInteger listPageCount = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/mcp";
        server.createContext("/custom/mcp", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            JsonNode request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "session-paged");
                    json(exchange, 200, """
                            {
                              "jsonrpc": "2.0",
                              "id": %s,
                              "result": {
                                "protocolVersion": "2025-03-26",
                                "capabilities": {"tools": {"listChanged": false}},
                                "serverInfo": {"name": "paged", "version": "1.0.0"}
                              }
                            }
                            """.formatted(request.path("id")));
                }
                case "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                }
                case "tools/list" -> {
                    listPageCount.incrementAndGet();
                    boolean secondPage = request.path("params").hasNonNull("cursor");
                    String result = secondPage
                            ? """
                              {"tools":[{
                                "name":"second_tool",
                                "inputSchema":{"type":"object","properties":{}}
                              }]}
                              """
                            : """
                              {"tools":[{
                                "name":"first_tool",
                                "inputSchema":{"type":"object","properties":{}}
                              }],"nextCursor":"page-2"}
                              """;
                    json(exchange, 200, """
                            {"jsonrpc":"2.0","id":%s,"result":%s}
                            """.formatted(request.path("id"), result));
                }
                case "tools/call" -> json(exchange, 200, """
                        {
                          "jsonrpc":"2.0",
                          "id":%s,
                          "result":{
                            "content":[{"type":"text","text":"remote validation failed"}],
                            "isError":true
                          }
                        }
                        """.formatted(request.path("id")));
                default -> json(exchange, 400, """
                        {"jsonrpc":"2.0","id":%s,"error":{"message":"unexpected method"}}
                        """.formatted(request.path("id")));
            }
        });
        server.start();

        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setHttpConnectTimeoutSeconds(2);
        properties.setHttpReadTimeoutSeconds(2);
        McpGatewayService gatewayService = new McpGatewayService(objectMapper, properties, allowPrivateOutboundPolicy());
        PreparedMcpServer preparedServer = new PreparedMcpServer(
                1L,
                "paged-server",
                baseUrl,
                McpTransportType.STREAMABLE_HTTP,
                null
        );

        assertThat(gatewayService.listTools(preparedServer))
                .extracting(DiscoveredMcpTool::name)
                .containsExactly("first_tool", "second_tool");
        assertThat(listPageCount.get()).isEqualTo(2);

        PreparedMcpTool tool = new PreparedMcpTool(
                2L,
                "first_tool",
                null,
                "{\"type\":\"object\"}",
                McpToolRiskLevel.LOW,
                preparedServer
        );
        McpToolCallResult result = gatewayService.callTool(tool, Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.modelVisibleContent()).isEqualTo("remote validation failed");
        assertThat(result.rawResponsePayload()).contains("\"isError\":true");
    }

    @Test
    void initializeResponseIsRejectedAtRawByteBoundary() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", exchange -> json(exchange, 200, "x".repeat(2_000)));
        server.start();
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxHttpResponseBytes(256);
        properties.setHttpConnectTimeoutSeconds(2);
        properties.setHttpReadTimeoutSeconds(2);
        McpGatewayService gatewayService = new McpGatewayService(
                objectMapper, properties, allowPrivateOutboundPolicy());
        PreparedMcpServer preparedServer = new PreparedMcpServer(
                1L, "oversized", baseUrl, McpTransportType.STREAMABLE_HTTP, null);

        assertThatThrownBy(() -> gatewayService.listTools(preparedServer))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("MCP_TOOL_RESPONSE_TOO_LARGE"));
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void sse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private OutboundUrlPolicy allowPrivateOutboundPolicy() {
        OutboundUrlProperties properties = new OutboundUrlProperties();
        properties.setAllowPrivateAddresses(true);
        properties.setAllowPlainHttp(true);
        return new OutboundUrlPolicy(properties);
    }
}
