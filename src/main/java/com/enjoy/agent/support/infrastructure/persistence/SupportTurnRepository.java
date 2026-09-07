package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 客服 Turn 仓储。 */
public interface SupportTurnRepository extends JpaRepository<SupportTurn, UUID> {

    long countByConversation_IdAndStatus(Long conversationId, SupportTurnStatus status);

    /**
     * 摘要刷新时取最新完成的 Turn，并把它们保留为原始上下文。返回条数由
     * Pageable 控制，当前轻量策略固定保留最近两个 Turn。
     */
    List<SupportTurn> findAllByConversation_IdAndStatusOrderByCompletedAtDesc(
            Long conversationId,
            SupportTurnStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SupportTurn> findByConversation_IdAndIdempotencyKey(Long conversationId, String idempotencyKey);

    boolean existsByConversation_IdAndStatus(Long conversationId, SupportTurnStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SupportTurn> findFirstByConversation_IdAndStatusOrderByCreatedAtAsc(
            Long conversationId,
            SupportTurnStatus status
    );

    /**
     * 只锁 support_turn 自身，不锁联表的 conversation/tenant/user。这样所有写路径
     * 都能保持“会话 -> Turn”顺序，避免 PostgreSQL 对整个 JOIN 做 FOR UPDATE 时
     * 与 beginTurn 形成反向锁等待。
     */
    @Query(value = """
            select support_turn.*
            from support_turn
            join support_conversation
              on support_conversation.id = support_turn.conversation_id
            where support_turn.id = :id
              and support_conversation.tenant_id = :tenantId
              and support_conversation.user_id = :userId
            for update of support_turn
            """, nativeQuery = true)
    Optional<SupportTurn> findByIdAndConversation_Tenant_IdAndConversation_User_Id(
            @Param("id") UUID id,
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId
    );
}
