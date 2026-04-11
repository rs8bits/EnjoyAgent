package com.enjoy.agent.mcp.application;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.mcp.api.request.CreateMcpServerRequest;
import com.enjoy.agent.mcp.api.request.UpdateMcpServerRequest;
import com.enjoy.agent.mcp.api.response.McpServerResponse;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.infrastructure.persistence.McpOAuthConnectionRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpServerRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP Server 应用服务。
 */
@Service
public class McpServerApplicationService {

    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;
    private final TenantRepository tenantRepository;
    private final CredentialRepository credentialRepository;
    private final McpOAuthConnectionRepository mcpOAuthConnectionRepository;

    public McpServerApplicationService(
            McpServerRepository mcpServerRepository,
            McpToolRepository mcpToolRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository,
            TenantRepository tenantRepository,
            CredentialRepository credentialRepository,
            McpOAuthConnectionRepository mcpOAuthConnectionRepository
    ) {
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolRepository = mcpToolRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
        this.tenantRepository = tenantRepository;
        this.credentialRepository = credentialRepository;
        this.mcpOAuthConnectionRepository = mcpOAuthConnectionRepository;
    }

    /**
     * 创建 MCP Server。
     */
    @Transactional
    public McpServerResponse createServer(CreateMcpServerRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();
        String normalizedName = normalizeName(request.name());

        if (mcpServerRepository.existsByTenant_IdAndName(tenantId, normalizedName)) {
            throw new ApiException("MCP_SERVER_NAME_DUPLICATED", "MCP server name already exists", HttpStatus.CONFLICT);
        }

        McpServer server = new McpServer();
        server.setTenant(requireActiveTenant(tenantId));
        server.setName(normalizedName);
        server.setDescription(normalizeDescription(request.description()));
        server.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        server.setTransportType(request.transportType());
        server.setAuthType(resolveAuthType(request.authType(), request.credentialId()));
        server.setCredential(resolveCredential(currentUser.userId(), server.getAuthType(), request.credentialId()));
        server.setEnabled(request.enabled() == null || request.enabled());

        return toResponse(mcpServerRepository.saveAndFlush(server));
    }

    /**
     * 查询当前租户下的 MCP Server 列表。
     */
    @Transactional(readOnly = true)
    public List<McpServerResponse> listServers() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return mcpServerRepository.findAllByTenant_IdOrderByIdDesc(currentUser.tenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询单个 MCP Server。
     */
    @Transactional(readOnly = true)
    public McpServerResponse getServer(Long id) {
        return toResponse(requireTenantOwnedServer(id));
    }

    /**
     * 更新 MCP Server。
     */
    @Transactional
    public McpServerResponse updateServer(Long id, UpdateMcpServerRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        McpServer server = requireTenantOwnedServer(id);
        String normalizedName = normalizeName(request.name());
        String normalizedBaseUrl = normalizeBaseUrl(request.baseUrl());
        McpAuthType resolvedAuthType = resolveAuthType(request.authType(), request.credentialId());

        if (mcpServerRepository.existsByTenant_IdAndNameAndIdNot(currentUser.tenantId(), normalizedName, id)) {
            throw new ApiException("MCP_SERVER_NAME_DUPLICATED", "MCP server name already exists", HttpStatus.CONFLICT);
        }

        boolean resetOAuthConnection = isOAuthAuthType(server.getAuthType())
                && (server.getAuthType() != resolvedAuthType || !server.getBaseUrl().equals(normalizedBaseUrl));

        server.setName(normalizedName);
        server.setDescription(normalizeDescription(request.description()));
        server.setBaseUrl(normalizedBaseUrl);
        server.setTransportType(request.transportType());
        server.setAuthType(resolvedAuthType);
        server.setCredential(resolveCredential(currentUser.userId(), server.getAuthType(), request.credentialId()));
        server.setEnabled(request.enabled());
        if (!isOAuthAuthType(server.getAuthType()) || resetOAuthConnection) {
            mcpOAuthConnectionRepository.deleteByServer_Id(server.getId());
        }

        return toResponse(mcpServerRepository.saveAndFlush(server));
    }

    /**
     * 删除 MCP Server，同时清理工具目录和 Agent 绑定关系。
     */
    @Transactional
    public void deleteServer(Long id) {
        McpServer server = requireTenantOwnedServer(id);
        mcpOAuthConnectionRepository.deleteByServer_Id(id);
        agentMcpToolBindingRepository.deleteAllByTool_Server_Id(id);
        mcpToolRepository.deleteAllByServer_Id(id);
        mcpServerRepository.delete(server);
    }

    /**
     * 供其他 MCP 服务复用的租户内实体校验。
     */
    @Transactional(readOnly = true)
    public McpServer requireTenantOwnedServer(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return mcpServerRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("MCP_SERVER_NOT_FOUND", "MCP server not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 确保当前租户可用。
     */
    private Tenant requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Current tenant not found", HttpStatus.FORBIDDEN));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException("TENANT_DISABLED", "Current tenant is disabled", HttpStatus.FORBIDDEN);
        }
        return tenant;
    }

    /**
     * 解析可用于 MCP Server 的凭证。
     * MVP 先要求显式使用 CUSTOM 类型的 API Key。
     */
    private Credential resolveCredential(Long userId, McpAuthType authType, Long credentialId) {
        if (authType == McpAuthType.NONE) {
            if (credentialId != null) {
                throw new ApiException("MCP_SERVER_AUTH_INVALID", "Anonymous MCP server should not carry credentialId", HttpStatus.BAD_REQUEST);
            }
            return null;
        }

        if (authType == McpAuthType.OAUTH_AUTH_CODE) {
            if (credentialId != null) {
                throw new ApiException("MCP_SERVER_AUTH_INVALID", "OAuth MCP server should not use static credentialId", HttpStatus.BAD_REQUEST);
            }
            return null;
        }

        if (authType == McpAuthType.OAUTH_CLIENT_CREDENTIALS) {
            if (credentialId != null) {
                throw new ApiException("MCP_SERVER_AUTH_INVALID", "OAuth MCP server should not use static credentialId", HttpStatus.BAD_REQUEST);
            }
            return null;
        }

        if (credentialId == null) {
            throw new ApiException("MCP_SERVER_CREDENTIAL_REQUIRED", "Static bearer MCP server requires credentialId", HttpStatus.BAD_REQUEST);
        }

        Credential credential = credentialRepository.findByIdAndUser_Id(credentialId, userId)
                .orElseThrow(() -> new ApiException("CREDENTIAL_NOT_FOUND", "Credential not found for current user", HttpStatus.BAD_REQUEST));
        if (credential.getStatus() != CredentialStatus.ACTIVE) {
            throw new ApiException("CREDENTIAL_DISABLED", "Credential is disabled", HttpStatus.BAD_REQUEST);
        }
        if (credential.getProvider() != CredentialProvider.CUSTOM) {
            throw new ApiException(
                    "CREDENTIAL_PROVIDER_INVALID",
                    "MCP server credential must use CUSTOM provider",
                    HttpStatus.BAD_REQUEST
            );
        }
        return credential;
    }

    private McpAuthType resolveAuthType(McpAuthType authType, Long credentialId) {
        if (authType != null) {
            return authType;
        }
        return credentialId == null ? McpAuthType.NONE : McpAuthType.STATIC_BEARER;
    }

    private boolean isOAuthAuthType(McpAuthType authType) {
        return authType == McpAuthType.OAUTH_AUTH_CODE || authType == McpAuthType.OAUTH_CLIENT_CREDENTIALS;
    }

    /**
     * 转换成接口返回对象。
     */
    private McpServerResponse toResponse(McpServer server) {
        Credential credential = server.getCredential();
        return new McpServerResponse(
                server.getId(),
                server.getTenant().getId(),
                server.getName(),
                server.getDescription(),
                server.getBaseUrl(),
                server.getTransportType().name(),
                server.getAuthType().name(),
                credential == null ? null : credential.getId(),
                credential == null ? null : credential.getName(),
                server.isEnabled(),
                mcpToolRepository.countByServer_Id(server.getId()),
                server.getLastSyncedAt(),
                server.getCreatedAt(),
                server.getUpdatedAt()
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

    /**
     * 统一收口基础地址写法，避免尾部斜杠带来的重复值。
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new ApiException("MCP_SERVER_BASE_URL_INVALID", "MCP server baseUrl must start with http:// or https://", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
