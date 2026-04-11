package com.enjoy.agent.mcp.application;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.domain.entity.McpToolCallLog;
import com.enjoy.agent.mcp.domain.enums.McpToolCallStatus;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP Tool 调用日志应用服务。
 */
@Service
public class McpToolCallLogService {

    private final McpToolCallLogRepository mcpToolCallLogRepository;

    public McpToolCallLogService(McpToolCallLogRepository mcpToolCallLogRepository) {
        this.mcpToolCallLogRepository = mcpToolCallLogRepository;
    }

    @Transactional
    public void recordSuccess(
            PreparedChatTurn preparedChatTurn,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            McpToolCallResult result,
            Long latencyMs
    ) {
        McpToolCallLog log = createBaseLog(preparedChatTurn, tool, toolCallId);
        log.setStatus(McpToolCallStatus.SUCCESS);
        log.setLatencyMs(latencyMs);
        log.setRequestPayload(truncate(requestPayload, 4000));
        log.setResponsePayload(truncate(result.rawResponsePayload(), 4000));
        mcpToolCallLogRepository.save(log);
    }

    @Transactional
    public void recordFailure(
            PreparedChatTurn preparedChatTurn,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            McpToolCallFailure failure
    ) {
        McpToolCallLog log = createBaseLog(preparedChatTurn, tool, toolCallId);
        log.setStatus(McpToolCallStatus.FAILED);
        log.setLatencyMs(failure.getLatencyMs());
        log.setRequestPayload(truncate(requestPayload, 4000));
        log.setErrorCode(failure.getCode());
        log.setErrorMessage(truncate(failure.getMessage(), 1000));
        mcpToolCallLogRepository.save(log);
    }

    private McpToolCallLog createBaseLog(PreparedChatTurn preparedChatTurn, PreparedMcpTool tool, String toolCallId) {
        McpToolCallLog log = new McpToolCallLog();
        log.setTenantId(preparedChatTurn.tenantId());
        log.setUserId(preparedChatTurn.userId());
        log.setAgentId(preparedChatTurn.agentId());
        log.setSessionId(preparedChatTurn.sessionId());
        log.setUserMessageId(preparedChatTurn.userMessageId());
        log.setServerId(tool.server().id());
        log.setToolId(tool.id());
        log.setToolCallId(toolCallId);
        log.setToolName(tool.name());
        return log;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
