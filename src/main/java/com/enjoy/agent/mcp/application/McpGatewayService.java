package com.enjoy.agent.mcp.application;

import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于 Spring AI 所集成 MCP Java SDK 的动态 MCP 网关。
 *
 * <p>每次操作仍按租户运行时快照创建短生命周期客户端，因此数据库中的 Server URL、
 * 静态 Bearer 或刷新后的 OAuth Token 不会进入全局 {@code spring.ai.mcp.*} 配置。
 * initialize、协议协商、Session header、JSON-RPC 与 SSE 响应解析均由 SDK 负责。</p>
 */
@Service
public class McpGatewayService {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayService.class);
    private static final String CLIENT_NAME = "enjoy-agent";
    private static final String CLIENT_VERSION = "0.0.1-SNAPSHOT";

    private final ObjectMapper objectMapper;
    private final McpRuntimeProperties runtimeProperties;
    private final OutboundUrlPolicy outboundUrlPolicy;

    public McpGatewayService(
            ObjectMapper objectMapper,
            McpRuntimeProperties runtimeProperties,
            OutboundUrlPolicy outboundUrlPolicy
    ) {
        this.objectMapper = objectMapper;
        this.runtimeProperties = runtimeProperties;
        this.outboundUrlPolicy = outboundUrlPolicy;
    }

    /**
     * 通过 tools/list 读取远端 MCP Server 的完整工具目录。
     * SDK 的无参 listTools 会自动处理 nextCursor 分页。
     */
    public List<DiscoveredMcpTool> listTools(PreparedMcpServer server) {
        validateSupportedTransport(server);
        McpSyncClient client = createClient(server);
        try {
            initialize(client, server);
            McpSchema.ListToolsResult result = invoke(
                    server,
                    "tools/list",
                    "MCP_SERVER_INVOCATION_FAILED",
                    "Failed to list MCP tools",
                    client::listTools
            );
            if (result == null || result.tools() == null) {
                throw invalidResponse("MCP server returned an empty tools/list result");
            }
            return result.tools().stream()
                    .map(tool -> new DiscoveredMcpTool(
                            tool.name(),
                            tool.description(),
                            tool.inputSchema() == null ? null : toJson(tool.inputSchema())
                    ))
                    .toList();
        } finally {
            closeQuietly(client, server);
        }
    }

    /**
     * 调用某个 MCP 工具并同时保留模型可见文本与完整协议结果。
     */
    public McpToolCallResult callTool(PreparedMcpTool tool, Map<String, Object> arguments) {
        try (McpTurnSession turnSession = openTurnSession()) {
            return turnSession.callTool(tool, arguments);
        }
    }

    public McpTurnSession openTurnSession() {
        return new McpTurnSession();
    }

    private McpToolCallResult callTool(
            PreparedMcpTool tool,
            Map<String, Object> arguments,
            McpSyncClient client
    ) {
        PreparedMcpServer server = tool.server();
        // Re-resolve and validate immediately before every runtime operation. This also
        // catches a destination whose DNS answer changed after the turn session opened.
        requireHttpEndpoint(server.baseUrl());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                tool.name(), arguments == null ? Map.of() : arguments);
        McpSchema.CallToolResult result = invoke(
                server, "tools/call", "MCP_SERVER_INVOCATION_FAILED", "Failed to invoke MCP tool",
                () -> client.callTool(request));
        if (result == null) {
            throw invalidResponse("MCP server returned an empty tools/call result");
        }
        String rawResponsePayload = toJson(result);
        return new McpToolCallResult(
                toModelVisibleContent(result, rawResponsePayload), rawResponsePayload,
                Boolean.TRUE.equals(result.isError()));
    }

    public final class McpTurnSession implements AutoCloseable {
        private final Map<Long, ClientHolder> clients = new LinkedHashMap<>();
        private boolean closed;
        private int toolCallCount;
        private int cumulativeResultChars;

        public McpToolCallResult callTool(PreparedMcpTool tool, Map<String, Object> arguments) {
            if (closed) {
                throw new IllegalStateException("MCP turn session is already closed");
            }
            if (toolCallCount >= runtimeProperties.getMaxToolCallsPerTurn()) {
                throw new ApiException("MCP_TOOL_CALL_LIMIT_EXCEEDED",
                        "MCP tool call limit exceeded for this turn", HttpStatus.BAD_REQUEST);
            }
            // Count the attempt before opening/using a client. Failed initialization,
            // invocation and oversized responses must still consume this turn's budget.
            toolCallCount++;
            PreparedMcpServer server = tool.server();
            ClientHolder holder = clients.computeIfAbsent(server.id(), ignored -> openClient(server));
            McpToolCallResult result = McpGatewayService.this.callTool(tool, arguments, holder.client());
            int responseChars = Math.max(
                    result.rawResponsePayload() == null ? 0 : result.rawResponsePayload().length(),
                    result.modelVisibleContent() == null ? 0 : result.modelVisibleContent().length());
            if (responseChars > runtimeProperties.getMaxToolResponseChars()) {
                throw new ApiException("MCP_TOOL_RESPONSE_TOO_LARGE",
                        "MCP tool response exceeded the configured limit", HttpStatus.BAD_GATEWAY);
            }
            cumulativeResultChars += result.modelVisibleContent() == null ? 0 : result.modelVisibleContent().length();
            if (cumulativeResultChars > runtimeProperties.getMaxCumulativeToolResultChars()) {
                throw new ApiException("MCP_TOOL_RESULT_BUDGET_EXCEEDED",
                        "Cumulative MCP tool results exceeded the turn budget", HttpStatus.BAD_REQUEST);
            }
            return result;
        }

        private ClientHolder openClient(PreparedMcpServer server) {
            validateSupportedTransport(server);
            McpSyncClient client = createClient(server);
            try {
                initialize(client, server);
                return new ClientHolder(server, client);
            } catch (RuntimeException ex) {
                closeQuietly(client, server);
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            clients.values().forEach(holder -> closeQuietly(holder.client(), holder.server()));
            clients.clear();
        }
    }

    private record ClientHolder(PreparedMcpServer server, McpSyncClient client) {
    }

    private McpSyncClient createClient(PreparedMcpServer server) {
        try {
            URI endpoint = requireHttpEndpoint(server.baseUrl());
            HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport
                    .builder(originOf(endpoint))
                    .endpoint(endpoint.toString())
                    .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                    .connectTimeout(Duration.ofSeconds(runtimeProperties.getHttpConnectTimeoutSeconds()))
                    .clientBuilder(new BoundedHttpClientBuilder(runtimeProperties.getMaxHttpResponseBytes())
                            .followRedirects(HttpClient.Redirect.NEVER))
                    .openConnectionOnStartup(false);

            if (StringUtils.hasText(server.bearerToken())) {
                String bearerToken = server.bearerToken();
                transportBuilder.httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + bearerToken));
            }

            return McpClient.sync(transportBuilder.build())
                    .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                    .requestTimeout(Duration.ofSeconds(runtimeProperties.getHttpReadTimeoutSeconds()))
                    .initializationTimeout(Duration.ofSeconds(runtimeProperties.getHttpReadTimeoutSeconds()))
                    .build();
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw gatewayException(
                    server,
                    "client/create",
                    "MCP_INITIALIZE_FAILED",
                    "Failed to create MCP client",
                    ex
            );
        }
    }

    private void initialize(McpSyncClient client, PreparedMcpServer server) {
        McpSchema.InitializeResult result = invoke(
                server,
                "initialize",
                "MCP_INITIALIZE_FAILED",
                "Failed to initialize MCP session",
                client::initialize
        );
        if (result == null) {
            throw invalidResponse("MCP server returned an empty initialize result");
        }
    }

    private <T> T invoke(
            PreparedMcpServer server,
            String operation,
            String errorCode,
            String message,
            Supplier<T> invocation
    ) {
        try {
            requireHttpEndpoint(server.baseUrl());
            return invocation.get();
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw gatewayException(server, operation, errorCode, message, ex);
        }
    }

    private String toModelVisibleContent(McpSchema.CallToolResult result, String rawResponsePayload) {
        List<String> values = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                    values.add(textContent.text());
                } else if (content != null) {
                    values.add(toJson(content));
                }
            }
        }
        if (!values.isEmpty()) {
            return String.join("\n", values);
        }
        if (result.structuredContent() != null) {
            return toJson(result.structuredContent());
        }
        return rawResponsePayload;
    }

    private URI requireHttpEndpoint(String value) {
        return outboundUrlPolicy.requireAllowedHttpUrl(value, "MCP_SERVER_BASE_URL_INVALID");
    }

    private String originOf(URI endpoint) {
        return endpoint.getScheme() + "://" + endpoint.getRawAuthority() + "/";
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

    private ApiException gatewayException(
            PreparedMcpServer server,
            String operation,
            String fallbackCode,
            String fallbackMessage,
            RuntimeException cause
    ) {
        log.warn(
                "MCP SDK operation failed, serverId={}, operation={}, errorType={}",
                server.id(),
                operation,
                cause.getClass().getSimpleName()
        );
        if (isInvalidResponse(cause)) {
            return invalidResponse("MCP server returned an invalid response");
        }
        if (hasCause(cause, McpResponseLimitException.class)) {
            return new ApiException(
                    "MCP_TOOL_RESPONSE_TOO_LARGE",
                    "MCP server response exceeded the configured byte limit",
                    HttpStatus.BAD_GATEWAY
            );
        }
        return new ApiException(
                fallbackCode,
                fallbackMessage,
                HttpStatus.BAD_GATEWAY
        );
    }

    private boolean isInvalidResponse(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JsonProcessingException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("deserializ")
                        || normalized.contains("parsing json-rpc")
                        || normalized.contains("unknown media type")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String exceptionDetail(Throwable throwable) {
        String detail = McpError.aggregateExceptionMessages(throwable);
        if (!StringUtils.hasText(detail)) {
            detail = throwable.getClass().getSimpleName();
        }
        return abbreviate(detail, 300);
    }

    private ApiException invalidResponse(String message) {
        return new ApiException("MCP_SERVER_RESPONSE_INVALID", message, HttpStatus.BAD_GATEWAY);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "JSON_SERIALIZATION_FAILED",
                    "Failed to serialize JSON payload",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private String abbreviate(String value, int maxChars) {
        if (!StringUtils.hasText(value) || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private void closeQuietly(McpSyncClient client, PreparedMcpServer server) {
        try {
            if (!client.closeGracefully()) {
                log.debug("MCP client did not close gracefully, serverId={}", server.id());
            }
        } catch (RuntimeException ex) {
            log.debug("Failed to close MCP client, serverId={}", server.id(), ex);
        }
    }
}
