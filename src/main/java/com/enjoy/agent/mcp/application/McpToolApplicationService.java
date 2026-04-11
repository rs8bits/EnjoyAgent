package com.enjoy.agent.mcp.application;

import com.enjoy.agent.mcp.api.request.ReplaceMcpServerToolsRequest;
import com.enjoy.agent.mcp.api.request.UpsertMcpToolRequest;
import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.time.Instant;
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
 * MCP Tool 目录应用服务。
 */
@Service
public class McpToolApplicationService {

    private final McpServerApplicationService mcpServerApplicationService;
    private final McpToolRepository mcpToolRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;

    public McpToolApplicationService(
            McpServerApplicationService mcpServerApplicationService,
            McpToolRepository mcpToolRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository
    ) {
        this.mcpServerApplicationService = mcpServerApplicationService;
        this.mcpToolRepository = mcpToolRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
    }

    /**
     * 替换某个 MCP Server 的完整工具目录。
     * 当前阶段先由业务接口提交目录快照，后续再接真实 MCP 发现。
     */
    @Transactional
    public List<McpToolResponse> replaceServerTools(Long serverId, ReplaceMcpServerToolsRequest request) {
        McpServer server = mcpServerApplicationService.requireTenantOwnedServer(serverId);
        List<UpsertMcpToolRequest> requests = request.tools() == null ? List.of() : request.tools();
        return replaceServerTools(server, requests);
    }

    /**
     * 用远端发现出来的工具定义替换当前工具目录。
     */
    @Transactional
    public List<McpToolResponse> replaceServerTools(Long serverId, List<DiscoveredMcpTool> discoveredTools) {
        McpServer server = mcpServerApplicationService.requireTenantOwnedServer(serverId);
        List<UpsertMcpToolRequest> requests = discoveredTools == null
                ? List.of()
                : discoveredTools.stream()
                        .map(tool -> new UpsertMcpToolRequest(
                                tool.name(),
                                tool.description(),
                                tool.inputSchemaJson(),
                                McpToolRiskLevel.LOW,
                                true
                        ))
                        .toList();
        return replaceServerTools(server, requests);
    }

    private List<McpToolResponse> replaceServerTools(McpServer server, List<UpsertMcpToolRequest> requests) {
        Set<String> incomingNames = new LinkedHashSet<>();
        for (UpsertMcpToolRequest toolRequest : requests) {
            String normalizedName = normalizeName(toolRequest.name());
            if (!incomingNames.add(normalizedName)) {
                throw new ApiException("MCP_TOOL_NAME_DUPLICATED", "Duplicated MCP tool name in request", HttpStatus.BAD_REQUEST);
            }
        }

        List<McpTool> existingTools = mcpToolRepository.findAllByServer_IdOrderByIdAsc(server.getId());
        Map<String, McpTool> existingByName = new LinkedHashMap<>();
        for (McpTool tool : existingTools) {
            existingByName.put(tool.getName(), tool);
        }

        List<Long> removedToolIds = existingTools.stream()
                .filter(tool -> !incomingNames.contains(tool.getName()))
                .map(McpTool::getId)
                .toList();
        if (!removedToolIds.isEmpty()) {
            agentMcpToolBindingRepository.deleteAllByTool_IdIn(removedToolIds);
            mcpToolRepository.deleteAllByIdInBatch(removedToolIds);
        }

        List<McpTool> toSave = new ArrayList<>(requests.size());
        for (UpsertMcpToolRequest toolRequest : requests) {
            String normalizedName = normalizeName(toolRequest.name());
            McpTool tool = existingByName.get(normalizedName);
            if (tool == null) {
                tool = new McpTool();
                tool.setTenant(server.getTenant());
                tool.setServer(server);
            }
            tool.setName(normalizedName);
            tool.setDescription(normalizeDescription(toolRequest.description()));
            tool.setInputSchemaJson(normalizeSchema(toolRequest.inputSchemaJson()));
            tool.setRiskLevel(toolRequest.riskLevel() == null ? McpToolRiskLevel.LOW : toolRequest.riskLevel());
            tool.setEnabled(toolRequest.enabled() == null || toolRequest.enabled());
            toSave.add(tool);
        }

        List<McpTool> savedTools = mcpToolRepository.saveAll(toSave);
        server.setLastSyncedAt(Instant.now());
        return savedTools.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询当前租户下的 MCP Tool 列表，可按 Server 过滤。
     */
    @Transactional(readOnly = true)
    public List<McpToolResponse> listTools(Long serverId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        if (serverId != null) {
            mcpServerApplicationService.requireTenantOwnedServer(serverId);
            return mcpToolRepository.findAllByServer_IdOrderByIdAsc(serverId)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        return mcpToolRepository.findAllByTenant_IdOrderByIdDesc(currentUser.tenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询单个 MCP Tool。
     */
    @Transactional(readOnly = true)
    public McpToolResponse getTool(Long id) {
        return toResponse(requireTenantOwnedTool(id));
    }

    /**
     * 校验并返回当前租户下的 MCP Tool。
     */
    @Transactional(readOnly = true)
    public McpTool requireTenantOwnedTool(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return mcpToolRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("MCP_TOOL_NOT_FOUND", "MCP tool not found", HttpStatus.NOT_FOUND));
    }

    private McpToolResponse toResponse(McpTool tool) {
        return new McpToolResponse(
                tool.getId(),
                tool.getTenant().getId(),
                tool.getServer().getId(),
                tool.getServer().getName(),
                tool.getName(),
                tool.getDescription(),
                tool.getInputSchemaJson(),
                tool.getRiskLevel().name(),
                tool.isEnabled(),
                tool.getCreatedAt(),
                tool.getUpdatedAt()
        );
    }

    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private String normalizeSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        return schemaJson.trim();
    }
}
