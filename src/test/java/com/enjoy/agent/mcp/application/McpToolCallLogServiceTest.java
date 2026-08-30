package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.domain.entity.McpToolCallLog;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpToolCallLogServiceTest {

    @Mock
    private McpToolCallLogRepository repository;

    @Mock
    private PreparedChatTurn turn;

    @Test
    void redactsNestedSecretsBeforePersistence() {
        when(turn.tenantId()).thenReturn(1L);
        when(turn.userId()).thenReturn(2L);
        when(turn.agentId()).thenReturn(3L);
        when(turn.sessionId()).thenReturn(4L);
        when(turn.userMessageId()).thenReturn(5L);
        PreparedMcpServer server = new PreparedMcpServer(
                6L, "server", "https://8.8.8.8/mcp", McpTransportType.STREAMABLE_HTTP, null);
        PreparedMcpTool tool = new PreparedMcpTool(
                7L, "tool", null, "{}", McpToolRiskLevel.LOW, server);
        McpToolCallLogService service = new McpToolCallLogService(repository, new ObjectMapper());

        service.recordSuccess(
                turn,
                tool,
                "call-1",
                "{\"input\":\"ok\",\"authorization\":\"Bearer secret-value\",\"nested\":{\"apiKey\":\"abc\"}}",
                new McpToolCallResult("ok", "{\"token\":\"response-secret\",\"ok\":true}"),
                10L
        );

        ArgumentCaptor<McpToolCallLog> captor = ArgumentCaptor.forClass(McpToolCallLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRequestPayload())
                .contains("[REDACTED]")
                .doesNotContain("secret-value", "abc");
        assertThat(captor.getValue().getResponsePayload())
                .contains("[REDACTED]")
                .doesNotContain("response-secret");
    }
}
