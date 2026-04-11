package com.enjoy.agent.tenant.infrastructure.persistence;

import com.enjoy.agent.tenant.domain.entity.TenantMember;
import com.enjoy.agent.tenant.domain.enums.TenantMemberStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 租户成员仓储接口。
 */
public interface TenantMemberRepository extends JpaRepository<TenantMember, Long> {

    /**
     * 按租户和用户查找唯一的成员关系，并预加载租户和用户对象。
     */
    @EntityGraph(attributePaths = {"tenant", "user"})
    Optional<TenantMember> findByTenant_IdAndUser_Id(Long tenantId, Long userId);

    /**
     * 读取用户的第一个有效租户成员关系。
     */
    @EntityGraph(attributePaths = {"tenant", "user"})
    Optional<TenantMember> findFirstByUser_IdAndStatusOrderByIdAsc(Long userId, TenantMemberStatus status);
}
