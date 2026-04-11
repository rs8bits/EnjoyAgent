package com.enjoy.agent.mcp.infrastructure.persistence;

import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Agent MCP Tool 绑定仓储。
 */
public interface AgentMcpToolBindingRepository extends JpaRepository<AgentMcpToolBinding, Long> {

    @EntityGraph(attributePaths = {"tenant", "agent", "tool", "tool.server", "tool.server.credential"})
    List<AgentMcpToolBinding> findAllByAgent_IdOrderByIdAsc(Long agentId);

    void deleteAllByAgent_Id(Long agentId);

    void deleteAllByTool_IdIn(Collection<Long> toolIds);

    void deleteAllByTool_Server_Id(Long serverId);
}
