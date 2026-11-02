package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.api.response.McpToolApprovalResponse;
import com.enjoy.agent.mcp.application.McpHighRiskApprovalService;
import com.enjoy.agent.mcp.application.McpToolApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Tool 查询接口。
 */
@Tag(name = "MCP Tools", description = "MCP Tool 查询接口")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/mcp/tools")
public class McpToolController {

    private final McpToolApplicationService mcpToolApplicationService;
    private final McpHighRiskApprovalService approvalService;

    public McpToolController(
            McpToolApplicationService mcpToolApplicationService,
            McpHighRiskApprovalService approvalService
    ) {
        this.mcpToolApplicationService = mcpToolApplicationService;
        this.approvalService = approvalService;
    }

    @Operation(summary = "MCP Tool 列表", description = "分页查询当前租户下的 MCP Tool 列表，可按 Server 过滤")
    @GetMapping
    public ApiResponse<PagedResponse<McpToolResponse>> listTools(
            @Parameter(description = "按 MCP Server 过滤，可选")
            @RequestParam(required = false) Long serverId,
            Pageable pageable
    ) {
        return ApiResponse.success(PagedResponse.of(mcpToolApplicationService.listTools(serverId, pageable)));
    }

    @Operation(summary = "MCP Tool 详情", description = "查询当前租户下某个 MCP Tool 的详情")
    @GetMapping("/{id}")
    public ApiResponse<McpToolResponse> getTool(@PathVariable Long id) {
        return ApiResponse.success(mcpToolApplicationService.getTool(id));
    }

    @Operation(summary = "审批一次 HIGH 风险工具调用", description = "签发仅绑定当前用户、Agent 与工具的短期令牌")
    @PostMapping("/{id}/approvals")
    public ApiResponse<McpToolApprovalResponse> approveHighRiskTool(
            @PathVariable Long id,
            @RequestParam Long agentId
    ) {
        throw new com.enjoy.agent.shared.exception.ApiException("MCP_PARAMETER_CONFIRMATION_REQUIRED",
                "请通过运行时确认卡片审核具体参数；工具级预授权已停用。", org.springframework.http.HttpStatus.GONE);
    }
}
