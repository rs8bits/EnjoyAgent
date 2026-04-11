package com.enjoy.agent.chat.infrastructure.persistence;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.chat.domain.entity.ChatSessionMemory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话记忆仓储接口。
 */
public interface ChatSessionMemoryRepository extends JpaRepository<ChatSessionMemory, Long> {

    /**
     * 查询某个会话在指定记忆策略下的摘要。
     */
    Optional<ChatSessionMemory> findBySession_IdAndTenant_IdAndMemoryType(Long sessionId, Long tenantId, MemoryStrategy memoryType);

    /**
     * 删除某个会话下的全部摘要记忆。
     */
    void deleteAllBySession_Id(Long sessionId);
}
