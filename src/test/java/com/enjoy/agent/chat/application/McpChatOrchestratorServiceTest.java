package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpRuntimeProperties;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpServer;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelCallLogService;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolCall;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpChatOrchestratorServiceTest {

    @Mock
    private ModelGatewayService modelGatewayService;

    @Mock
    private ModelCallLogService modelCallLogService;

    @Mock
    private McpGatewayService mcpGatewayService;

    @Mock
    private McpToolCallLogService mcpToolCallLogService;

    @Test
    void completeTurn_truncatesToolResultBeforeSendingBackToModel() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(3);
        properties.setMaxToolResultChars(5);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallLogService,
                mcpGatewayService,
                mcpToolCallLogService,
                properties,
                new ObjectMapper()
        );

        PreparedChatTurn preparedChatTurn = preparedChatTurn();
        ModelGatewayToolCall toolCall = new ModelGatewayToolCall("call-1", "demo_tool", "{\"foo\":\"bar\"}");
        when(modelGatewayService.generateChatCompletion(any(), anyList(), anyList()))
                .thenReturn(new ModelGatewayChatCompletion(
                        null,
                        List.of(toolCall),
                        CredentialProvider.OPENAI,
                        "qwen-plus",
                        CredentialSource.USER,
                        1L,
                        100L,
                        10,
                        5,
                        15
                ))
                .thenReturn(new ModelGatewayChatCompletion(
                        "final",
                        List.of(),
                        CredentialProvider.OPENAI,
                        "qwen-plus",
                        CredentialSource.USER,
                        1L,
                        100L,
                        20,
                        10,
                        30
                ));
        when(mcpGatewayService.callTool(any(), any()))
                .thenReturn(new McpToolCallResult("123456789", "{\"ok\":true}"));

        service.completeTurn(preparedChatTurn);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelGatewayConversationMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelGatewayService, times(2)).generateChatCompletion(any(), messagesCaptor.capture(), anyList());
        List<ModelGatewayConversationMessage> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        ModelGatewayConversationMessage toolMessage = secondRoundMessages.get(secondRoundMessages.size() - 1);
        assertThat(toolMessage.role()).isEqualTo("tool");
        assertThat(toolMessage.content()).isEqualTo("12345");
    }

    @Test
    void completeTurn_throwsWhenMaxRoundsExceeded() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(1);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallLogService,
                mcpGatewayService,
                mcpToolCallLogService,
                properties,
                new ObjectMapper()
        );

        PreparedChatTurn preparedChatTurn = preparedChatTurn();
        ModelGatewayToolCall toolCall = new ModelGatewayToolCall("call-1", "demo_tool", "{\"foo\":\"bar\"}");
        when(modelGatewayService.generateChatCompletion(any(), anyList(), anyList()))
                .thenReturn(new ModelGatewayChatCompletion(
                        null,
                        List.of(toolCall),
                        CredentialProvider.OPENAI,
                        "qwen-plus",
                        CredentialSource.USER,
                        1L,
                        100L,
                        10,
                        5,
                        15
                ));
        when(mcpGatewayService.callTool(any(), any()))
                .thenReturn(new McpToolCallResult("ok", "{\"ok\":true}"));

        assertThatThrownBy(() -> service.completeTurn(preparedChatTurn))
                .isInstanceOf(ModelGatewayInvocationException.class)
                .satisfies(ex -> assertThat(((ModelGatewayInvocationException) ex).getCode())
                        .isEqualTo("MODEL_TOOL_LOOP_EXCEEDED"));
    }

    private PreparedChatTurn preparedChatTurn() {
        return new PreparedChatTurn(
                1L,
                2L,
                3L,
                4L,
                5L,
                "hello",
                "system",
                null,
                new PreparedModelConfig(
                        CredentialProvider.OPENAI,
                        ModelType.CHAT,
                        "qwen-plus",
                        CredentialSource.USER,
                        1L,
                        null,
                        BigDecimal.valueOf(0.1),
                        512
                ),
                null,
                List.of(new PreparedMcpTool(
                        10L,
                        "demo_tool",
                        "demo",
                        "{\"type\":\"object\"}",
                        McpToolRiskLevel.LOW,
                        new PreparedMcpServer(
                                20L,
                                "demo-server",
                                "https://example.com/mcp",
                                McpTransportType.STREAMABLE_HTTP,
                                null
                        )
                )),
                null,
                null,
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "hello"))
        );
    }
}
