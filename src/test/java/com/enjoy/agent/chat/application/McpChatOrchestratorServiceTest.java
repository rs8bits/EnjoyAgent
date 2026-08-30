package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.mcp.application.McpRuntimeProperties;
import com.enjoy.agent.mcp.application.McpToolCallFailure;
import com.enjoy.agent.mcp.application.McpToolCallLogService;
import com.enjoy.agent.mcp.application.McpHighRiskApprovalService;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.mcp.application.McpToolCallResult;
import com.enjoy.agent.mcp.application.PreparedMcpServer;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelCallAccountingService;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolCall;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolDefinition;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpChatOrchestratorServiceTest {

    @Mock
    private ModelGatewayService modelGatewayService;

    @Mock
    private ModelCallAccountingService modelCallAccountingService;

    @Mock
    private McpGatewayService mcpGatewayService;

    @Mock
    private McpGatewayService.McpTurnSession gatewaySession;

    @Mock
    private McpToolCallLogService mcpToolCallLogService;

    @Mock
    private McpHighRiskApprovalService approvalService;

    @Mock
    private BillingUsageApplicationService billingUsageApplicationService;

    @BeforeEach
    void setUpGatewaySession() {
        when(mcpGatewayService.openTurnSession()).thenReturn(gatewaySession);
    }

    @Test
    void completeTurn_truncatesToolResultBeforeSendingBackToModel() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(1);
        properties.setMaxToolResultChars(5);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallAccountingService,
                mcpGatewayService,
                mcpToolCallLogService,
                approvalService,
                properties,
                new ObjectMapper(),
                billingUsageApplicationService
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
        when(gatewaySession.callTool(any(), any()))
                .thenReturn(new McpToolCallResult("123456789", "{\"ok\":true}"));

        service.completeTurn(preparedChatTurn);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelGatewayConversationMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelGatewayToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelGatewayService, times(2)).generateChatCompletion(
                any(),
                messagesCaptor.capture(),
                toolsCaptor.capture()
        );
        List<ModelGatewayConversationMessage> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        ModelGatewayConversationMessage toolMessage = secondRoundMessages.get(secondRoundMessages.size() - 1);
        assertThat(toolMessage.role()).isEqualTo("tool");
        assertThat(toolMessage.content()).isEqualTo("12345");
        assertThat(toolsCaptor.getAllValues().get(0)).isNotEmpty();
        assertThat(toolsCaptor.getAllValues().get(1)).isEmpty();
        verify(modelCallAccountingService, times(2)).recordSuccess(any(), any(), any());
    }

    @Test
    void completeTurn_throwsWhenMaxRoundsExceeded() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(1);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallAccountingService,
                mcpGatewayService,
                mcpToolCallLogService,
                approvalService,
                properties,
                new ObjectMapper(),
                billingUsageApplicationService
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
        when(gatewaySession.callTool(any(), any()))
                .thenReturn(new McpToolCallResult("ok", "{\"ok\":true}"));

        assertThatThrownBy(() -> service.completeTurn(preparedChatTurn))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MODEL_TOOL_LOOP_EXCEEDED"));
        verify(modelGatewayService, times(2)).generateChatCompletion(any(), anyList(), anyList());
        verify(modelCallAccountingService, times(2)).recordSuccess(any(), any(), any());
        verify(gatewaySession, times(1)).callTool(any(), any());
    }

    @Test
    void completeTurn_returnsProtocolToolErrorToModelAndRecordsFailure() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(2);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallAccountingService,
                mcpGatewayService,
                mcpToolCallLogService,
                approvalService,
                properties,
                new ObjectMapper(),
                billingUsageApplicationService
        );

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
                        "recovered",
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
        when(gatewaySession.callTool(any(), any()))
                .thenReturn(new McpToolCallResult("remote failed", "{\"isError\":true}", true));

        service.completeTurn(preparedChatTurn());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelGatewayConversationMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelGatewayService, times(2)).generateChatCompletion(any(), messagesCaptor.capture(), anyList());
        List<ModelGatewayConversationMessage> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        assertThat(secondRoundMessages.get(secondRoundMessages.size() - 1).content())
                .isEqualTo("Tool call failed: MCP_TOOL_REPORTED_ERROR - remote failed");
        verify(mcpToolCallLogService).recordFailure(
                any(),
                any(),
                eq("call-1"),
                eq("{\"foo\":\"bar\"}"),
                eq("{\"isError\":true}"),
                any()
        );
    }

    @Test
    void completeTurn_rejectsDuplicateToolNamesBeforeInvokingModel() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallAccountingService,
                mcpGatewayService,
                mcpToolCallLogService,
                approvalService,
                properties,
                new ObjectMapper(),
                billingUsageApplicationService
        );
        PreparedMcpTool first = preparedChatTurn().mcpTools().get(0);
        PreparedMcpTool second = new PreparedMcpTool(
                11L,
                "demo_tool",
                "duplicate",
                "{\"type\":\"object\"}",
                McpToolRiskLevel.LOW,
                new PreparedMcpServer(
                        21L,
                        "second-server",
                        "https://second.example.com/mcp",
                        McpTransportType.STREAMABLE_HTTP,
                        null
                )
        );

        assertThatThrownBy(() -> service.completeTurn(preparedChatTurn(List.of(first, second))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("MCP_TOOL_NAME_CONFLICT"));
        verifyNoInteractions(modelGatewayService);
    }

    @Test
    void completeTurn_preservesGatewayErrorCodeInToolFailureAudit() {
        McpRuntimeProperties properties = new McpRuntimeProperties();
        properties.setMaxToolRounds(1);
        McpChatOrchestratorService service = new McpChatOrchestratorService(
                modelGatewayService,
                modelCallAccountingService,
                mcpGatewayService,
                mcpToolCallLogService,
                approvalService,
                properties,
                new ObjectMapper(),
                billingUsageApplicationService
        );
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
                        "recovered",
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
        when(gatewaySession.callTool(any(), any())).thenThrow(new ApiException(
                "MCP_SERVER_INVOCATION_FAILED",
                "remote unavailable",
                org.springframework.http.HttpStatus.BAD_GATEWAY
        ));

        assertThat(service.completeTurn(preparedChatTurn()).content()).isEqualTo("recovered");

        ArgumentCaptor<McpToolCallFailure> failureCaptor = ArgumentCaptor.forClass(McpToolCallFailure.class);
        verify(mcpToolCallLogService).recordFailure(
                any(),
                any(),
                eq("call-1"),
                eq("{\"foo\":\"bar\"}"),
                failureCaptor.capture()
        );
        assertThat(failureCaptor.getValue().getCode()).isEqualTo("MCP_SERVER_INVOCATION_FAILED");
    }

    private PreparedChatTurn preparedChatTurn() {
        return preparedChatTurn(List.of(new PreparedMcpTool(
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
        )));
    }

    private PreparedChatTurn preparedChatTurn(List<PreparedMcpTool> tools) {
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
                tools,
                null,
                null,
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "hello")),
                null
        );
    }
}
