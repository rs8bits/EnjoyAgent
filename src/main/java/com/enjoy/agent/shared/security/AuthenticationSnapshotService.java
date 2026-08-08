package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.domain.enums.UserStatus;
import com.enjoy.agent.shared.config.CacheNames;
import com.enjoy.agent.tenant.domain.entity.TenantMember;
import com.enjoy.agent.tenant.domain.enums.TenantMemberStatus;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantMemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加载 JWT 对应的当前成员安全快照。
 *
 * <p>快照来自数据库而不是盲信 JWT 中的可变字段。短 TTL 与跨实例失效消息共同限制
 * 成员禁用、角色调整等操作的生效窗口。</p>
 */
@Service
public class AuthenticationSnapshotService {

    private final TenantMemberRepository tenantMemberRepository;
    private final Timer membershipLookupTimer;

    public AuthenticationSnapshotService(
            TenantMemberRepository tenantMemberRepository,
            MeterRegistry meterRegistry
    ) {
        this.tenantMemberRepository = tenantMemberRepository;
        this.membershipLookupTimer = Timer.builder("enjoy.auth.membership.lookup")
                .description("Database lookup time for an uncached authenticated tenant membership snapshot")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Cacheable(
            value = CacheNames.AUTH_CONTEXT,
            key = "T(com.enjoy.agent.shared.config.CacheKeys).authenticationContext(#tenantId, #userId)",
            condition = "@cacheProperties.isAuthContextEnabled()",
            sync = true
    )
    @Transactional(readOnly = true)
    public AuthenticatedUser load(Long tenantId, Long userId) {
        TenantMember member = membershipLookupTimer.record(() ->
                        tenantMemberRepository.findByTenant_IdAndUser_Id(tenantId, userId))
                .orElseThrow(() -> new BadCredentialsException("Authentication context not found"));
        validateMember(member);
        return toAuthenticatedUser(member);
    }

    /**
     * 成员、用户或租户安全属性变更后调用。两级缓存会将真实失效延迟到事务提交。
     */
    @CacheEvict(
            value = CacheNames.AUTH_CONTEXT,
            key = "T(com.enjoy.agent.shared.config.CacheKeys).authenticationContext(#tenantId, #userId)"
    )
    public void evict(Long tenantId, Long userId) {
        // Cache advice performs the actual invalidation.
    }

    private void validateMember(TenantMember member) {
        if (member.getStatus() != TenantMemberStatus.ACTIVE) {
            throw new BadCredentialsException("Tenant membership is disabled");
        }
        if (member.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BadCredentialsException("User is disabled");
        }
        if (member.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new BadCredentialsException("Tenant is disabled");
        }
    }

    private AuthenticatedUser toAuthenticatedUser(TenantMember member) {
        return new AuthenticatedUser(
                member.getUser().getId(),
                member.getUser().getEmail(),
                member.getUser().getDisplayName(),
                member.getTenant().getId(),
                member.getTenant().getCode(),
                member.getTenant().getName(),
                member.getRole(),
                member.getUser().getSystemRole()
        );
    }
}
