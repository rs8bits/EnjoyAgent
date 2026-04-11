package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.response.McpToolCallLogResponse;
import com.enjoy.agent.mcp.application.McpToolCallLogApplicationService;
import com.enjoy.agent.mcp.domain.enums.McpToolCallStatus;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 工具调用日志接口。
 */
@Tag(name = "MCP Tool Call Logs", description = "查询 MCP 工具调用日志")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/mcp/tool-call-logs")
public class McpToolCallLogController {

    private final McpToolCallLogApplicationService mcpToolCallLogApplicationService;

    public McpToolCallLogController(McpToolCallLogApplicationService mcpToolCallLogApplicationService) {
        this.mcpToolCallLogApplicationService = mcpToolCallLogApplicationService;
    }

    @Operation(summary = "查询 MCP 工具调用日志", description = "按 session、agent、状态过滤最近的工具调用日志，便于联调和排障")
    @GetMapping
    public ApiResponse<List<McpToolCallLogResponse>> listLogs(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) McpToolCallStatus status,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(mcpToolCallLogApplicationService.listLogs(sessionId, agentId, status, limit));
    }
}
