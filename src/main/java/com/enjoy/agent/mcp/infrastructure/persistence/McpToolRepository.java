package com.enjoy.agent.mcp.infrastructure.persistence;

import com.enjoy.agent.mcp.domain.entity.McpTool;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP Tool 仓储。
 */
public interface McpToolRepository extends JpaRepository<McpTool, Long> {

    @EntityGraph(attributePaths = {"tenant", "server", "server.credential"})
    List<McpTool> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    @EntityGraph(attributePaths = {"tenant", "server", "server.credential"})
    List<McpTool> findAllByServer_IdOrderByIdAsc(Long serverId);

    @EntityGraph(attributePaths = {"tenant", "server", "server.credential"})
    Optional<McpTool> findByIdAndTenant_Id(Long id, Long tenantId);

    @EntityGraph(attributePaths = {"tenant", "server", "server.credential"})
    List<McpTool> findAllByIdInAndTenant_Id(Collection<Long> ids, Long tenantId);

    long countByServer_Id(Long serverId);

    void deleteAllByServer_Id(Long serverId);
}
