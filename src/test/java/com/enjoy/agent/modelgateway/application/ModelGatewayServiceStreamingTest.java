package com.enjoy.agent.modelgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
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
                objectMapper
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
                        512
                ),
                null,
                List.of(),
                null,
                null,
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "你好"))
        );
    }

    private void sse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
