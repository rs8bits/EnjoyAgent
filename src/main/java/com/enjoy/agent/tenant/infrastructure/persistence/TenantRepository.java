package com.enjoy.agent.tenant.infrastructure.persistence;

import com.enjoy.agent.tenant.domain.entity.Tenant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 租户仓储接口。
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /**
     * 按租户编码查找。
     */
    Optional<Tenant> findByCode(String code);

    /**
     * 判断租户编码是否已存在。
     */
    boolean existsByCode(String code);
}
