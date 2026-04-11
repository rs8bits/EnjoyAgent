package com.enjoy.agent.mcp.infrastructure.persistence;

import com.enjoy.agent.mcp.domain.entity.McpToolCallLog;
import com.enjoy.agent.mcp.domain.enums.McpToolCallStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * MCP Tool 调用日志仓储。
 */
public interface McpToolCallLogRepository extends JpaRepository<McpToolCallLog, Long> {

    @Query("""
            select log
            from McpToolCallLog log
            where log.tenantId = :tenantId
              and (:sessionId is null or log.sessionId = :sessionId)
              and (:agentId is null or log.agentId = :agentId)
              and (:status is null or log.status = :status)
            order by log.createdAt desc, log.id desc
            """)
    List<McpToolCallLog> search(
            @Param("tenantId") Long tenantId,
            @Param("sessionId") Long sessionId,
            @Param("agentId") Long agentId,
            @Param("status") McpToolCallStatus status,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * 删除某个会话下的 MCP Tool 调用日志。
     */
    void deleteAllBySessionId(Long sessionId);
}
