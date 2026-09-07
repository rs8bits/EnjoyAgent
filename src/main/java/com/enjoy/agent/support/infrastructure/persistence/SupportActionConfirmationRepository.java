package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportActionConfirmation;
import com.enjoy.agent.support.domain.enums.SupportActionConfirmationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 客服确认提案仓储；读取确认记录时必须同时带上当前 tenantId 和 userId。 */
public interface SupportActionConfirmationRepository
        extends JpaRepository<SupportActionConfirmation, UUID> {

    @EntityGraph(attributePaths = {"conversation", "sourceTurn", "tenant", "user", "executionTurn"})
    Optional<SupportActionConfirmation> findBySourceTurn_IdAndSkillNameAndArgumentsHash(
            UUID sourceTurnId,
            String skillName,
            String argumentsHash
    );

    /** 页面刷新时只恢复仍可操作的记录，不把其他用户或已过期提案暴露出去。 */
    @EntityGraph(attributePaths = "sourceTurn")
    List<SupportActionConfirmation>
            findAllByTenant_IdAndUser_IdAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
                    Long tenantId,
                    Long userId,
                    SupportActionConfirmationStatus status,
                    Instant now
            );

    /**
     * 确认和取消都锁定同一行，保证双击、重复请求或两个浏览器标签最多执行一次。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select confirmation
            from SupportActionConfirmation confirmation
            where confirmation.id = :id
              and confirmation.tenant.id = :tenantId
              and confirmation.user.id = :userId
            """)
    Optional<SupportActionConfirmation> findOwnedByIdForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId
    );
}
