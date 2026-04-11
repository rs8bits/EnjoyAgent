package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            JsonNode request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            lastAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastSessionHeader.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));

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
                                "capabilities": {},
                                "serverInfo": {
                                  "name": "mock",
                                  "version": "1.0.0"
                                }
                              }
                            }
                            """.formatted(request.path("id").asLong()));
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
                        """.formatted(request.path("id").asLong()));
                case "tools/call" -> json(exchange, 200, """
                        {
                          "jsonrpc": "2.0",
                          "id": %s,
                          "result": {
                            "content": [
                              {
                                "type": "text",
                                "text": "tool-ok"
                              }
                            ],
                            "structuredContent": {
                              "ok": true
                            }
                          }
                        }
                        """.formatted(request.path("id").asLong()));
                default -> json(exchange, 400, """
                        {
                          "jsonrpc": "2.0",
                          "id": %s,
                          "error": {
                            "message": "unexpected method"
                          }
                        }
                        """.formatted(request.path("id").asLong()));
            }
        });
        server.start();

        McpRuntimeProperties properties = new McpRuntimeProperties();
        McpGatewayService gatewayService = new McpGatewayService(objectMapper, properties);
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
        McpGatewayService gatewayService = new McpGatewayService(objectMapper, properties);
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

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
