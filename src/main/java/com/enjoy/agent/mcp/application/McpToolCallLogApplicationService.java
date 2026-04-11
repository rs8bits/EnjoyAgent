package com.enjoy.agent.mcp.application;

import com.enjoy.agent.mcp.api.response.McpToolCallLogResponse;
import com.enjoy.agent.mcp.domain.entity.McpToolCallLog;
import com.enjoy.agent.mcp.domain.enums.McpToolCallStatus;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 工具调用日志查询服务。
 */
@Service
public class McpToolCallLogApplicationService {

    private final McpToolCallLogRepository mcpToolCallLogRepository;

    public McpToolCallLogApplicationService(McpToolCallLogRepository mcpToolCallLogRepository) {
        this.mcpToolCallLogRepository = mcpToolCallLogRepository;
    }

    @Transactional(readOnly = true)
    public List<McpToolCallLogResponse> listLogs(Long sessionId, Long agentId, McpToolCallStatus status, Integer limit) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return mcpToolCallLogRepository.search(
                        currentUser.tenantId(),
                        sessionId,
                        agentId,
                        status,
                        PageRequest.of(0, size)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private McpToolCallLogResponse toResponse(McpToolCallLog log) {
        return new McpToolCallLogResponse(
                log.getId(),
                log.getAgentId(),
                log.getSessionId(),
                log.getServerId(),
                log.getToolId(),
                log.getToolCallId(),
                log.getToolName(),
                log.getStatus().name(),
                log.getLatencyMs(),
                log.getErrorCode(),
                log.getErrorMessage(),
                log.getRequestPayload(),
                log.getResponsePayload(),
                log.getCreatedAt()
        );
    }
}
