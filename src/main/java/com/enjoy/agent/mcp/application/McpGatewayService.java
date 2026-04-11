package com.enjoy.agent.mcp.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * MCP HTTP 网关。
 */
@Service
public class McpGatewayService {

    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdSequence = new AtomicLong(1);

    public McpGatewayService(ObjectMapper objectMapper, McpRuntimeProperties runtimeProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(runtimeProperties.getHttpConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(runtimeProperties.getHttpReadTimeoutSeconds()));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 通过 tools/list 读取远端 MCP Server 的工具目录。
     */
    public List<DiscoveredMcpTool> listTools(PreparedMcpServer server) {
        McpSession session = initialize(server);
        List<DiscoveredMcpTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode response = postJsonRpc(session, "tools/list", params, true);
            JsonNode result = response.path("result");
            for (JsonNode toolNode : result.path("tools")) {
                JsonNode inputSchema = toolNode.path("inputSchema");
                tools.add(new DiscoveredMcpTool(
                        toolNode.path("name").asText(),
                        textOrNull(toolNode.get("description")),
                        inputSchema.isMissingNode() || inputSchema.isNull() ? null : toJson(inputSchema)
                ));
            }
            cursor = textOrNull(result.get("nextCursor"));
        } while (cursor != null);
        return tools;
    }

    /**
     * 调用某个 MCP 工具。
     */
    public McpToolCallResult callTool(PreparedMcpTool tool, Map<String, Object> arguments) {
        McpSession session = initialize(tool.server());
        JsonNode params = objectMapper.createObjectNode()
                .put("name", tool.name())
                .set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));

        JsonNode response = postJsonRpc(session, "tools/call", params, true);
        JsonNode result = response.path("result");
        return new McpToolCallResult(toModelVisibleContent(result), toJson(result));
    }

    private McpSession initialize(PreparedMcpServer server) {
        validateSupportedTransport(server);
        long requestId = nextRequestId();
        ObjectNode initializeParams = objectMapper.createObjectNode();
        initializeParams.put("protocolVersion", MCP_PROTOCOL_VERSION);
        initializeParams.set("capabilities", objectMapper.createObjectNode());
        initializeParams.set("clientInfo", objectMapper.createObjectNode()
                .put("name", "enjoy-agent")
                .put("version", "0.0.1-SNAPSHOT"));

        JsonNode initializeRequest = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", "initialize")
                .set("params", initializeParams);

        McpHttpResponse entity = restClient.post()
                .uri(URI.create(server.baseUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> applyAuth(server, headers))
                .body(toJson(initializeRequest))
                .exchange((request, clientResponse) -> {
                    String body = clientResponse.bodyTo(String.class);
                    return new McpHttpResponse(
                            body,
                            clientResponse.getHeaders().getFirst("Mcp-Session-Id"),
                            clientResponse.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            clientResponse.getStatusCode()
                    );
                });

        if (!entity.status().is2xxSuccessful()) {
            throw new ApiException(
                    "MCP_INITIALIZE_FAILED",
                    "Failed to initialize MCP session: " + describeHttpFailure(entity.status(), entity.body()),
                    HttpStatus.BAD_GATEWAY
            );
        }

        JsonNode initializeResponse = parseJsonRpcResponse(entity.body(), entity.contentType(), requestId);
        if (initializeResponse.has("error")) {
            throw new ApiException(
                    "MCP_INITIALIZE_FAILED",
                    "MCP server initialize returned error: " + extractJsonRpcErrorMessage(initializeResponse),
                    HttpStatus.BAD_GATEWAY
            );
        }

        postInitializedNotification(server, entity.sessionId());
        return new McpSession(server, entity.sessionId());
    }

    private void postInitializedNotification(PreparedMcpServer server, String sessionId) {
        JsonNode initializedRequest = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized");

        HttpStatusCode status = restClient.post()
                .uri(URI.create(server.baseUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    applyAuth(server, headers);
                    if (StringUtils.hasText(sessionId)) {
                        headers.add("Mcp-Session-Id", sessionId);
                    }
                })
                .body(toJson(initializedRequest))
                .exchange((request, clientResponse) -> HttpStatus.valueOf(clientResponse.getStatusCode().value()));

        if (!status.is2xxSuccessful()) {
            throw new ApiException("MCP_INITIALIZE_FAILED", "MCP server did not accept initialized notification", HttpStatus.BAD_GATEWAY);
        }
    }

    private JsonNode postJsonRpc(McpSession session, String method, JsonNode params, boolean responseRequired) {
        long requestId = nextRequestId();
        JsonNode requestBody = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", method)
                .set("params", params == null ? objectMapper.createObjectNode() : params);

        McpHttpResponse response = restClient.post()
                .uri(URI.create(session.server().baseUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    applyAuth(session.server(), headers);
                    if (StringUtils.hasText(session.sessionId())) {
                        headers.add("Mcp-Session-Id", session.sessionId());
                    }
                })
                .body(toJson(requestBody))
                .exchange((request, clientResponse) -> new McpHttpResponse(
                        clientResponse.bodyTo(String.class),
                        clientResponse.getHeaders().getFirst("Mcp-Session-Id"),
                        clientResponse.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        clientResponse.getStatusCode()
                ));

        if (!response.status().is2xxSuccessful()) {
            throw new ApiException(
                    "MCP_SERVER_INVOCATION_FAILED",
                    "MCP server invocation failed: " + describeHttpFailure(response.status(), response.body()),
                    HttpStatus.BAD_GATEWAY
            );
        }
        if (!responseRequired) {
            return objectMapper.createObjectNode();
        }
        JsonNode json = parseJsonRpcResponse(response.body(), response.contentType(), requestId);
        if (json.has("error")) {
            throw new ApiException(
                    "MCP_SERVER_INVOCATION_FAILED",
                    extractJsonRpcErrorMessage(json),
                    HttpStatus.BAD_GATEWAY
            );
        }
        return json;
    }

    private JsonNode parseJsonRpcResponse(String rawBody, String contentType, long requestId) {
        if (!StringUtils.hasText(rawBody)) {
            throw new ApiException("MCP_SERVER_RESPONSE_INVALID", "MCP server returned empty response", HttpStatus.BAD_GATEWAY);
        }
        try {
            if (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                JsonNode sseJson = parseSseJsonRpc(rawBody, requestId);
                if (sseJson != null) {
                    return sseJson;
                }
            }
            JsonNode json = objectMapper.readTree(rawBody);
            if (json.isArray()) {
                for (JsonNode item : json) {
                    if (item.path("id").asLong(-1) == requestId) {
                        return item;
                    }
                }
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new ApiException("MCP_SERVER_RESPONSE_INVALID", "Failed to parse MCP server response", HttpStatus.BAD_GATEWAY);
        }
    }

    private JsonNode parseSseJsonRpc(String rawBody, long requestId) throws JsonProcessingException {
        String[] events = rawBody.split("\\R\\R");
        for (String event : events) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\\R")) {
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            if (data.isEmpty()) {
                continue;
            }
            JsonNode json = objectMapper.readTree(data.toString());
            if (json.path("id").asLong(-1) == requestId) {
                return json;
            }
        }
        return null;
    }

    private void applyAuth(PreparedMcpServer server, HttpHeaders headers) {
        if (!StringUtils.hasText(server.bearerToken())) {
            return;
        }
        headers.setBearerAuth(server.bearerToken());
    }

    private String toModelVisibleContent(JsonNode result) {
        List<String> texts = new ArrayList<>();
        for (JsonNode contentNode : result.path("content")) {
            if ("text".equals(contentNode.path("type").asText()) && contentNode.hasNonNull("text")) {
                texts.add(contentNode.path("text").asText());
            } else if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                texts.add(toJson(contentNode));
            }
        }
        if (!texts.isEmpty()) {
            return String.join("\n", texts);
        }
        JsonNode structuredContent = result.get("structuredContent");
        if (structuredContent != null && !structuredContent.isNull()) {
            return toJson(structuredContent);
        }
        return toJson(result);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException("JSON_SERIALIZATION_FAILED", "Failed to serialize JSON payload", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private long nextRequestId() {
        return requestIdSequence.getAndIncrement();
    }

    private void validateSupportedTransport(PreparedMcpServer server) {
        if (server.transportType() == McpTransportType.STREAMABLE_HTTP) {
            return;
        }
        throw new ApiException(
                "MCP_TRANSPORT_NOT_SUPPORTED",
                "Current runtime only supports STREAMABLE_HTTP MCP servers",
                HttpStatus.NOT_IMPLEMENTED
        );
    }

    private String extractJsonRpcErrorMessage(JsonNode response) {
        String message = response.path("error").path("message").asText("MCP invocation failed");
        String data = textOrNull(response.path("error").get("data"));
        if (data == null) {
            return message;
        }
        return message + " (" + abbreviate(data, 300) + ")";
    }

    private String describeHttpFailure(HttpStatusCode status, String body) {
        String message = "HTTP " + status.value();
        if (!StringUtils.hasText(body)) {
            return message;
        }
        return message + " - " + abbreviate(body, 300);
    }

    private String abbreviate(String value, int maxChars) {
        if (!StringUtils.hasText(value) || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private record McpSession(PreparedMcpServer server, String sessionId) {
    }

    private record McpHttpResponse(String body, String sessionId, String contentType, HttpStatusCode status) {
    }
}
