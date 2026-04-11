package com.enjoy.agent.chat.infrastructure.persistence;

import com.enjoy.agent.chat.domain.entity.ChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天会话仓储接口。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 查询当前租户下的全部会话，并预加载 Agent 和创建人信息。
     */
    @EntityGraph(attributePaths = {"tenant", "agent", "createdBy"})
    List<ChatSession> findAllByTenant_IdOrderByUpdatedAtDesc(Long tenantId);

    /**
     * 按 Agent 过滤当前租户下的会话列表。
     */
    @EntityGraph(attributePaths = {"tenant", "agent", "createdBy"})
    List<ChatSession> findAllByTenant_IdAndAgent_IdOrderByUpdatedAtDesc(Long tenantId, Long agentId);

    /**
     * 查询当前租户下的单个会话，并预加载运行时需要的 Agent、模型和凭证。
     */
    @EntityGraph(attributePaths = {"tenant", "agent", "agent.modelConfig", "agent.modelConfig.credential", "createdBy"})
    Optional<ChatSession> findByIdAndTenant_Id(Long id, Long tenantId);
}
