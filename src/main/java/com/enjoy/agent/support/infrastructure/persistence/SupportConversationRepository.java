package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportConversation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 客服会话仓储。所有业务查询都必须同时携带 tenantId 与 userId。 */
public interface SupportConversationRepository extends JpaRepository<SupportConversation, Long> {

    /**
     * PostgreSQL 原子 get-or-create 的写入半步。ON CONFLICT 让多个实例首次同时访问
     * 时只有一个插入成功，另一个事务随后读取同一条唯一会话。
     */
    @Modifying
    @Query(value = """
            insert into support_conversation (tenant_id, user_id, last_message_sequence, created_at, updated_at)
            values (:tenantId, :userId, 0, now(), now())
            on conflict (tenant_id, user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @EntityGraph(attributePaths = {"tenant", "user"})
    Optional<SupportConversation> findByTenant_IdAndUser_Id(Long tenantId, Long userId);

    /**
     * 写消息前锁定唯一会话，序列分配与“仅一轮运行”检查都在同一事务中完成。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"tenant", "user"})
    @Query("""
            select conversation
            from SupportConversation conversation
            where conversation.id = :id
              and conversation.tenant.id = :tenantId
              and conversation.user.id = :userId
            """)
    Optional<SupportConversation> findOwnedByIdForUpdate(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId
    );
}
