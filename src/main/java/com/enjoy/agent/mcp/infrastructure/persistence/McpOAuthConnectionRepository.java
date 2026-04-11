package com.enjoy.agent.mcp.infrastructure.persistence;

import com.enjoy.agent.mcp.domain.entity.McpOAuthConnection;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP OAuth 连接仓储。
 */
public interface McpOAuthConnectionRepository extends JpaRepository<McpOAuthConnection, Long> {

    @EntityGraph(attributePaths = {"tenant", "server", "connectedByUser"})
    Optional<McpOAuthConnection> findByServer_IdAndTenant_Id(Long serverId, Long tenantId);

    @EntityGraph(attributePaths = {"tenant", "server", "connectedByUser"})
    Optional<McpOAuthConnection> findByAuthorizationState(String authorizationState);

    void deleteByServer_Id(Long serverId);
}
