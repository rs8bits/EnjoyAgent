package com.enjoy.agent.mcp.application;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.mcp.api.request.ReplaceAgentToolBindingsRequest;
import com.enjoy.agent.mcp.api.response.AgentToolBindingResponse;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 与 MCP Tool 绑定应用服务。
 */
@Service
public class AgentToolBindingApplicationService {

    private final AgentRepository agentRepository;
    private final McpToolRepository mcpToolRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;

    public AgentToolBindingApplicationService(
            AgentRepository agentRepository,
            McpToolRepository mcpToolRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository
    ) {
        this.agentRepository = agentRepository;
        this.mcpToolRepository = mcpToolRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
    }

    /**
     * 查询某个 Agent 当前绑定的工具列表。
     */
    @Transactional(readOnly = true)
    public List<AgentToolBindingResponse> listBindings(Long agentId) {
        requireTenantOwnedAgent(agentId);
        return agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(agentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 替换某个 Agent 当前绑定的全部工具。
     */
    @Transactional
    public List<AgentToolBindingResponse> replaceBindings(Long agentId, ReplaceAgentToolBindingsRequest request) {
        Agent agent = requireTenantOwnedAgent(agentId);
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        List<Long> toolIds = request.toolIds() == null ? List.of() : request.toolIds();

        Set<Long> uniqueToolIds = new LinkedHashSet<>(toolIds);
        List<McpTool> requestedTools = uniqueToolIds.isEmpty()
                ? List.of()
                : mcpToolRepository.findAllByIdInAndTenant_Id(uniqueToolIds, currentUser.tenantId());

        if (requestedTools.size() != uniqueToolIds.size()) {
            throw new ApiException("MCP_TOOL_NOT_FOUND", "Some MCP tools do not exist in current tenant", HttpStatus.BAD_REQUEST);
        }
        requestedTools.forEach(this::validateRunnableTool);
        validateUniqueToolNames(requestedTools);

        List<AgentMcpToolBinding> existingBindings = agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(agentId);
        Map<Long, AgentMcpToolBinding> existingByToolId = new LinkedHashMap<>();
        for (AgentMcpToolBinding binding : existingBindings) {
            existingByToolId.put(binding.getTool().getId(), binding);
        }

        List<AgentMcpToolBinding> toDelete = existingBindings.stream()
                .filter(binding -> !uniqueToolIds.contains(binding.getTool().getId()))
                .toList();
        if (!toDelete.isEmpty()) {
            agentMcpToolBindingRepository.deleteAllInBatch(toDelete);
        }

        List<AgentMcpToolBinding> toSave = new ArrayList<>();
        for (McpTool tool : requestedTools) {
            AgentMcpToolBinding binding = existingByToolId.get(tool.getId());
            if (binding == null) {
                binding = new AgentMcpToolBinding();
                binding.setTenant(agent.getTenant());
                binding.setAgent(agent);
                binding.setTool(tool);
            }
            binding.setEnabled(true);
            toSave.add(binding);
        }
        if (!toSave.isEmpty()) {
            agentMcpToolBindingRepository.saveAll(toSave);
        }

        return agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(agentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 校验当前租户下的 Agent。
     */
    private Agent requireTenantOwnedAgent(Long agentId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return agentRepository.findByIdAndTenant_Id(agentId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("AGENT_NOT_FOUND", "Agent not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 当前阶段只允许绑定启用中的工具，且其 Server 也必须启用。
     */
    private void validateRunnableTool(McpTool tool) {
        if (!tool.isEnabled()) {
            throw new ApiException("MCP_TOOL_DISABLED", "MCP tool is disabled", HttpStatus.BAD_REQUEST);
        }
        if (!tool.getServer().isEnabled()) {
            throw new ApiException("MCP_SERVER_DISABLED", "MCP server is disabled", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 一个 Agent 当前只能绑定一组全局唯一名称的工具，避免模型工具定义冲突。
     */
    private void validateUniqueToolNames(List<McpTool> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (McpTool tool : tools) {
            if (!names.add(tool.getName())) {
                throw new ApiException(
                        "MCP_TOOL_NAME_CONFLICT",
                        "Agent cannot bind multiple tools with the same name",
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }

    private AgentToolBindingResponse toResponse(AgentMcpToolBinding binding) {
        McpTool tool = binding.getTool();
        return new AgentToolBindingResponse(
                binding.getId(),
                binding.getTenant().getId(),
                binding.getAgent().getId(),
                tool.getId(),
                tool.getName(),
                tool.getServer().getId(),
                tool.getServer().getName(),
                tool.getRiskLevel().name(),
                binding.isEnabled(),
                binding.getCreatedAt(),
                binding.getUpdatedAt()
        );
    }
}
