package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.application.McpToolApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    public McpToolController(McpToolApplicationService mcpToolApplicationService) {
        this.mcpToolApplicationService = mcpToolApplicationService;
    }

    @Operation(summary = "MCP Tool 列表", description = "查询当前租户下的 MCP Tool 列表，可按 Server 过滤")
    @GetMapping
    public ApiResponse<List<McpToolResponse>> listTools(
            @Parameter(description = "按 MCP Server 过滤，可选")
            @RequestParam(required = false) Long serverId
    ) {
        return ApiResponse.success(mcpToolApplicationService.listTools(serverId));
    }

    @Operation(summary = "MCP Tool 详情", description = "查询当前租户下某个 MCP Tool 的详情")
    @GetMapping("/{id}")
    public ApiResponse<McpToolResponse> getTool(@PathVariable Long id) {
        return ApiResponse.success(mcpToolApplicationService.getTool(id));
    }
}
