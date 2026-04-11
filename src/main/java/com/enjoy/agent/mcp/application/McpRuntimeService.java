package com.enjoy.agent.mcp.application;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpServerRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 运行时与远端同步服务。
 */
@Service
public class McpRuntimeService {

    private final McpServerRepository mcpServerRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;
    private final McpGatewayService mcpGatewayService;
    private final McpToolApplicationService mcpToolApplicationService;
    private final McpOAuthApplicationService mcpOAuthApplicationService;
    private final AesCryptoService aesCryptoService;

    public McpRuntimeService(
            McpServerRepository mcpServerRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository,
            McpGatewayService mcpGatewayService,
            McpToolApplicationService mcpToolApplicationService,
            McpOAuthApplicationService mcpOAuthApplicationService,
            AesCryptoService aesCryptoService
    ) {
        this.mcpServerRepository = mcpServerRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
        this.mcpGatewayService = mcpGatewayService;
        this.mcpToolApplicationService = mcpToolApplicationService;
        this.mcpOAuthApplicationService = mcpOAuthApplicationService;
        this.aesCryptoService = aesCryptoService;
    }

    /**
     * 为聊天运行时提供 Agent 当前可调用的工具快照。
     */
    @Transactional
    public List<PreparedMcpTool> listRunnableToolsForAgent(Long agentId) {
        LinkedHashMap<Long, PreparedMcpServer> preparedServers = new LinkedHashMap<>();
        List<AgentMcpToolBinding> bindings = agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(agentId);
        return bindings.stream()
                .filter(AgentMcpToolBinding::isEnabled)
                .map(AgentMcpToolBinding::getTool)
                .filter(McpTool::isEnabled)
                .filter(tool -> tool.getServer().isEnabled())
                .map(tool -> toPreparedTool(tool, preparedServers))
                .toList();
    }

    /**
     * 从远端 MCP Server 同步工具目录。
     */
    @Transactional
    public List<McpToolResponse> syncToolsFromServer(Long serverId) {
        McpServer server = requireTenantOwnedServer(serverId);
        PreparedMcpServer preparedServer = toPreparedServer(server);
        List<DiscoveredMcpTool> discoveredTools = mcpGatewayService.listTools(preparedServer);
        server.setLastSyncedAt(Instant.now());
        return mcpToolApplicationService.replaceServerTools(serverId, discoveredTools);
    }

    /**
     * 供运行时与管理接口共享的 MCP Server 实体校验。
     */
    @Transactional(readOnly = true)
    public McpServer requireTenantOwnedServer(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return mcpServerRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("MCP_SERVER_NOT_FOUND", "MCP server not found", HttpStatus.NOT_FOUND));
    }

    private PreparedMcpTool toPreparedTool(McpTool tool, LinkedHashMap<Long, PreparedMcpServer> preparedServers) {
        return new PreparedMcpTool(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                tool.getInputSchemaJson(),
                tool.getRiskLevel() == null ? McpToolRiskLevel.LOW : tool.getRiskLevel(),
                preparedServers.computeIfAbsent(tool.getServer().getId(), ignored -> toPreparedServer(tool.getServer()))
        );
    }

    private PreparedMcpServer toPreparedServer(McpServer server) {
        Credential credential = server.getCredential();
        String bearerToken = null;
        if (server.getAuthType() == McpAuthType.STATIC_BEARER) {
            bearerToken = credential == null ? null : aesCryptoService.decrypt(credential.getSecretCiphertext());
        } else if (isOAuthAuthType(server.getAuthType())) {
            bearerToken = mcpOAuthApplicationService.resolveAccessTokenForRuntime(server);
        }
        return new PreparedMcpServer(
                server.getId(),
                server.getName(),
                server.getBaseUrl(),
                server.getTransportType(),
                bearerToken
        );
    }

    private boolean isOAuthAuthType(McpAuthType authType) {
        return authType == McpAuthType.OAUTH_AUTH_CODE || authType == McpAuthType.OAUTH_CLIENT_CREDENTIALS;
    }
}
