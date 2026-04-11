package com.enjoy.agent.agent.api;

import com.enjoy.agent.agent.api.request.CreateAgentRequest;
import com.enjoy.agent.agent.api.request.UpdateAgentRequest;
import com.enjoy.agent.agent.api.response.AgentResponse;
import com.enjoy.agent.agent.application.AgentApplicationService;
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
 * Agent 管理接口。
 */
@Tag(name = "Agent", description = "管理当前租户下的 Agent 基础配置")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentApplicationService agentApplicationService;

    public AgentController(AgentApplicationService agentApplicationService) {
        this.agentApplicationService = agentApplicationService;
    }

    /**
     * 创建 Agent。
     */
    @Operation(summary = "创建 Agent", description = "在当前租户下创建一个可绑定模型配置的 Agent")
    @PostMapping
    public ApiResponse<AgentResponse> createAgent(@Valid @RequestBody CreateAgentRequest request) {
        return ApiResponse.success(agentApplicationService.createAgent(request), "Agent created");
    }

    /**
     * 查询当前租户下的 Agent 列表。
     */
    @Operation(summary = "Agent 列表", description = "查询当前租户下的全部 Agent")
    @GetMapping
    public ApiResponse<List<AgentResponse>> listAgents() {
        return ApiResponse.success(agentApplicationService.listAgents());
    }

    /**
     * 查询当前租户下的 Agent 详情。
     */
    @Operation(summary = "Agent 详情", description = "按 ID 查询当前租户下的 Agent")
    @GetMapping("/{id}")
    public ApiResponse<AgentResponse> getAgent(@PathVariable Long id) {
        return ApiResponse.success(agentApplicationService.getAgent(id));
    }

    /**
     * 更新 Agent。
     */
    @Operation(summary = "更新 Agent", description = "更新当前租户下的 Agent 基础配置")
    @PutMapping("/{id}")
    public ApiResponse<AgentResponse> updateAgent(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        return ApiResponse.success(agentApplicationService.updateAgent(id, request), "Agent updated");
    }

    /**
     * 删除 Agent。
     */
    @Operation(summary = "删除 Agent", description = "删除当前租户下的 Agent")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAgent(@PathVariable Long id) {
        agentApplicationService.deleteAgent(id);
        return ApiResponse.success(null, "Agent deleted");
    }
}
