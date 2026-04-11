package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.request.CreateMcpServerRequest;
import com.enjoy.agent.mcp.api.request.ReplaceMcpServerToolsRequest;
import com.enjoy.agent.mcp.api.request.UpdateMcpServerRequest;
import com.enjoy.agent.mcp.api.response.McpServerResponse;
import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.application.McpServerApplicationService;
import com.enjoy.agent.mcp.application.McpToolApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Server 管理接口。
 */
@Tag(name = "MCP Servers", description = "MCP Server 注册与工具目录管理接口")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final McpServerApplicationService mcpServerApplicationService;
    private final McpToolApplicationService mcpToolApplicationService;
    private final McpRuntimeService mcpRuntimeService;

    public McpServerController(
            McpServerApplicationService mcpServerApplicationService,
            McpToolApplicationService mcpToolApplicationService,
            McpRuntimeService mcpRuntimeService
    ) {
        this.mcpServerApplicationService = mcpServerApplicationService;
        this.mcpToolApplicationService = mcpToolApplicationService;
        this.mcpRuntimeService = mcpRuntimeService;
    }

    @Operation(summary = "创建 MCP Server", description = "在当前租户下创建一个 MCP Server 配置")
    @PostMapping
    public ApiResponse<McpServerResponse> createServer(@Valid @RequestBody CreateMcpServerRequest request) {
        return ApiResponse.success(mcpServerApplicationService.createServer(request), "MCP server created");
    }

    @Operation(summary = "MCP Server 列表", description = "查询当前租户下的全部 MCP Server")
    @GetMapping
    public ApiResponse<List<McpServerResponse>> listServers() {
        return ApiResponse.success(mcpServerApplicationService.listServers());
    }

    @Operation(summary = "MCP Server 详情", description = "查询当前租户下某个 MCP Server 的详情")
    @GetMapping("/{id}")
    public ApiResponse<McpServerResponse> getServer(@PathVariable Long id) {
        return ApiResponse.success(mcpServerApplicationService.getServer(id));
    }

    @Operation(summary = "更新 MCP Server", description = "更新 MCP Server 的名称、地址、凭证和启用状态")
    @PutMapping("/{id}")
    public ApiResponse<McpServerResponse> updateServer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMcpServerRequest request
    ) {
        return ApiResponse.success(mcpServerApplicationService.updateServer(id, request), "MCP server updated");
    }

    @Operation(summary = "删除 MCP Server", description = "删除 MCP Server，并级联清理该 Server 下的工具目录和 Agent 绑定关系")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteServer(@PathVariable Long id) {
        mcpServerApplicationService.deleteServer(id);
        return ApiResponse.success(null, "MCP server deleted");
    }

    @Operation(summary = "替换工具目录", description = "用一份完整工具快照替换某个 MCP Server 当前的工具目录")
    @PutMapping("/{id}/tools")
    public ApiResponse<List<McpToolResponse>> replaceServerTools(
            @PathVariable Long id,
            @Valid @RequestBody ReplaceMcpServerToolsRequest request
    ) {
        return ApiResponse.success(mcpToolApplicationService.replaceServerTools(id, request), "MCP tools synchronized");
    }

    @Operation(summary = "从远端同步工具目录", description = "通过 MCP tools/list 从远端 Server 拉取最新工具目录并替换本地快照")
    @PostMapping("/{id}/sync-tools")
    public ApiResponse<List<McpToolResponse>> syncServerTools(@PathVariable Long id) {
        return ApiResponse.success(mcpRuntimeService.syncToolsFromServer(id), "MCP tools synchronized from remote server");
    }
}
