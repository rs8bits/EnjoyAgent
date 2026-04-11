package com.enjoy.agent.mcp.infrastructure.persistence;

import com.enjoy.agent.mcp.domain.entity.McpServer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP Server 仓储。
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    @EntityGraph(attributePaths = {"tenant", "credential"})
    List<McpServer> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    @EntityGraph(attributePaths = {"tenant", "credential"})
    Optional<McpServer> findByIdAndTenant_Id(Long id, Long tenantId);

    boolean existsByTenant_IdAndName(Long tenantId, String name);

    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);
}
