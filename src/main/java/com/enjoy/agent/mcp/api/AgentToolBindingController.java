package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.request.ReplaceAgentToolBindingsRequest;
import com.enjoy.agent.mcp.api.response.AgentToolBindingResponse;
import com.enjoy.agent.mcp.application.AgentToolBindingApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 与 MCP Tool 绑定管理接口。
 */
@Tag(name = "Agent Tool Bindings", description = "管理 Agent 与 MCP Tool 的绑定关系")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/agents/{agentId}/tool-bindings")
public class AgentToolBindingController {

    private final AgentToolBindingApplicationService agentToolBindingApplicationService;

    public AgentToolBindingController(AgentToolBindingApplicationService agentToolBindingApplicationService) {
        this.agentToolBindingApplicationService = agentToolBindingApplicationService;
    }

    @Operation(summary = "Agent 工具绑定列表", description = "查询某个 Agent 当前绑定的全部 MCP Tool")
    @GetMapping
    public ApiResponse<List<AgentToolBindingResponse>> listBindings(@PathVariable Long agentId) {
        return ApiResponse.success(agentToolBindingApplicationService.listBindings(agentId));
    }

    @Operation(summary = "替换 Agent 工具绑定", description = "用一组 MCP Tool ID 替换某个 Agent 当前绑定的全部工具")
    @PutMapping
    public ApiResponse<List<AgentToolBindingResponse>> replaceBindings(
            @PathVariable Long agentId,
            @Valid @RequestBody ReplaceAgentToolBindingsRequest request
    ) {
        return ApiResponse.success(
                agentToolBindingApplicationService.replaceBindings(agentId, request),
                "Agent tool bindings updated"
        );
    }
}
