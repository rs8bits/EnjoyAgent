package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportConversationMemory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 客服摘要仓储。
 *
 * <p>方法名故意同时包含 conversation、tenant 和 user。即使调用方拿到了其他
 * 会话的数字 ID，也无法借此读取不属于当前认证作用域的摘要。</p>
 */
public interface SupportConversationMemoryRepository
        extends JpaRepository<SupportConversationMemory, Long> {

    Optional<SupportConversationMemory> findByConversation_IdAndTenant_IdAndUser_Id(
            Long conversationId,
            Long tenantId,
            Long userId
    );
}
