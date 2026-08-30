package com.enjoy.agent.modelgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.enjoy.agent.shared.security.OutboundUrlProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelGatewayServiceStreamingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamReply_collectsStreamingDeltasIntoFinalResponse() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sse(exchange, """
                    data: {"choices":[{"delta":{"content":"你"}}]}

                    data: {"choices":[{"delta":{"content":"好"}}],"usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}

                    data: [DONE]

                    """);
        });
        server.start();

        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPlatformOpenaiApiKey("platform-test-key");

        ModelGatewayService service = new ModelGatewayService(
                mock(AesCryptoService.class),
                properties,
                objectMapper,
                allowLocalOutboundPolicy()
        );

        List<String> deltas = new ArrayList<>();
        ModelGatewayResult result = service.streamReply(preparedChatTurn(), deltas::add);

        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.provider()).isEqualTo(CredentialProvider.OPENAI);
        assertThat(result.modelName()).isEqualTo("qwen-plus");
        assertThat(result.credentialSource()).isEqualTo(CredentialSource.PLATFORM);
        assertThat(result.promptTokens()).isEqualTo(11);
        assertThat(result.completionTokens()).isEqualTo(2);
        assertThat(result.totalTokens()).isEqualTo(13);
        assertThat(deltas).containsExactly("你", "好");
        assertThat(authorizationHeader.get()).isEqualTo("Bearer platform-test-key");

        JsonNode json = objectMapper.readTree(requestBody.get());
        assertThat(json.path("stream").asBoolean()).isTrue();
        assertThat(json.path("model").asText()).isEqualTo("qwen-plus");
    }

    @Test
    void generateChatCompletion_mapsSpringAiToolCallsAndUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, """
                    {
                      "id": "chatcmpl-test",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [
                              {
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                  "name": "lookup_weather",
                                  "arguments": "{\\\"city\\\":\\\"Shanghai\\\"}"
                                }
                              }
                            ]
                          },
                          "finish_reason": "tool_calls"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 4,
                        "total_tokens": 16
                      }
                    }
                    """);
        });
        server.start();

        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPlatformOpenaiApiKey("platform-test-key");
        ModelGatewayService service = new ModelGatewayService(
                mock(AesCryptoService.class), properties, objectMapper, allowLocalOutboundPolicy());

        ModelGatewayChatCompletion completion = service.generateChatCompletion(
                preparedChatTurn().modelConfig(),
                "你是一个助手",
                List.of(ModelGatewayConversationMessage.user("上海天气怎么样？")),
                List.of(new ModelGatewayToolDefinition(
                        "lookup_weather",
                        "查询城市天气",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"
                ))
        );

        assertThat(completion.content()).isNull();
        assertThat(completion.toolCalls()).containsExactly(
                new ModelGatewayToolCall("call-1", "lookup_weather", "{\"city\":\"Shanghai\"}")
        );
        assertThat(completion.promptTokens()).isEqualTo(12);
        assertThat(completion.completionTokens()).isEqualTo(4);
        assertThat(completion.totalTokens()).isEqualTo(16);

        JsonNode json = objectMapper.readTree(requestBody.get());
        assertThat(json.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(json.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("lookup_weather");
    }

    @Test
    void generateChatCompletion_preservesToolCallHistoryForExplicitOrchestration() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, """
                    {
                      "choices": [{
                        "message": {"role": "assistant", "content": "上海当前晴，26℃"}
                      }],
                      "usage": {"prompt_tokens": 20, "completion_tokens": 6, "total_tokens": 26}
                    }
                    """);
        });
        server.start();

        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPlatformOpenaiApiKey("platform-test-key");
        ModelGatewayService service = new ModelGatewayService(
                mock(AesCryptoService.class), properties, objectMapper, allowLocalOutboundPolicy());

        ModelGatewayChatCompletion completion = service.generateChatCompletion(
                preparedChatTurn().modelConfig(),
                "你是一个助手",
                List.of(
                        ModelGatewayConversationMessage.user("上海天气怎么样？"),
                        ModelGatewayConversationMessage.assistantToolCalls(List.of(
                                new ModelGatewayToolCall("call-1", "lookup_weather", "{\"city\":\"Shanghai\"}")
                        )),
                        ModelGatewayConversationMessage.tool("call-1", "{\"temperature\":26,\"condition\":\"sunny\"}")
                ),
                List.of(new ModelGatewayToolDefinition(
                        "lookup_weather",
                        "查询城市天气",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"
                ))
        );

        assertThat(completion.content()).isEqualTo("上海当前晴，26℃");

        JsonNode json = objectMapper.readTree(requestBody.get());
        JsonNode assistantToolCall = json.path("messages").get(2);
        assertThat(assistantToolCall.path("role").asText()).isEqualTo("assistant");
        assertThat(assistantToolCall.path("tool_calls").get(0).path("id").asText()).isEqualTo("call-1");
        assertThat(assistantToolCall.path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("lookup_weather");
        JsonNode toolResponse = json.path("messages").get(3);
        assertThat(toolResponse.path("role").asText()).isEqualTo("tool");
        assertThat(toolResponse.path("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(toolResponse.path("content").asText()).contains("sunny");
    }

    @Test
    void syncResponseIsRejectedAtRawByteBoundaryBeforeCodecAggregation() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> json(exchange, """
                {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                """.formatted("x".repeat(2_000))));
        server.start();
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPlatformOpenaiApiKey("platform-test-key");
        properties.setMaxResponseBytes(256);
        ModelGatewayService service = new ModelGatewayService(
                mock(AesCryptoService.class), properties, objectMapper, allowLocalOutboundPolicy());

        assertThatThrownBy(() -> service.generateChatCompletion(
                preparedChatTurn().modelConfig(), "system",
                List.of(ModelGatewayConversationMessage.user("hello")), List.of()))
                .isInstanceOfSatisfying(ModelGatewayInvocationException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("MODEL_RESPONSE_TOO_LARGE"));
    }

    @Test
    void interruptedCallerFailsBeforeOpeningAnOutboundModelConnection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            json(exchange, "{\"choices\":[{\"message\":{\"content\":\"unexpected\"}}]}");
        });
        server.start();
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPlatformOpenaiApiKey("platform-test-key");
        ModelGatewayService service = new ModelGatewayService(
                mock(AesCryptoService.class), properties, objectMapper, allowLocalOutboundPolicy());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.generateChatCompletion(
                    preparedChatTurn().modelConfig(), "system",
                    List.of(ModelGatewayConversationMessage.user("hello")), List.of()))
                    .isInstanceOfSatisfying(ModelGatewayInvocationException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo("MODEL_INVOCATION_CANCELLED"));
        } finally {
            Thread.interrupted();
        }
        assertThat(requests).hasValue(0);
    }

    private PreparedChatTurn preparedChatTurn() {
        return new PreparedChatTurn(
                1L,
                2L,
                3L,
                4L,
                5L,
                "你好",
                "你是一个助手",
                null,
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.CHAT,
                        "qwen-plus",
                        CredentialSource.PLATFORM,
                        null,
                        null,
                        BigDecimal.valueOf(0.2),
                        512,
                        null,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        null,
                        null,
                        null
                ),
                null,
                List.of(),
                null,
                null,
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "你好")),
                null
        );
    }

    private OutboundUrlPolicy allowLocalOutboundPolicy() {
        OutboundUrlProperties properties = new OutboundUrlProperties();
        properties.setAllowPrivateAddresses(true);
        properties.setAllowPlainHttp(true);
        return new OutboundUrlPolicy(properties);
    }

    private void sse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
