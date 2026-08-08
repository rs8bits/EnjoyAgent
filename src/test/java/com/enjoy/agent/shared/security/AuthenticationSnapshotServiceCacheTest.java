package com.enjoy.agent.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.domain.enums.UserStatus;
import com.enjoy.agent.shared.config.CacheNames;
import com.enjoy.agent.shared.config.CacheProperties;
import com.enjoy.agent.shared.cache.CacheInvalidationBus;
import com.enjoy.agent.shared.cache.TwoLevelCache;
import com.enjoy.agent.shared.cache.TwoLevelCacheManager;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.entity.TenantMember;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberStatus;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantMemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.List;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuthenticationSnapshotServiceCacheTest.TestConfig.class)
class AuthenticationSnapshotServiceCacheTest {

    @Autowired
    private AuthenticationSnapshotService service;

    @Autowired
    private TenantMemberRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheProperties cacheProperties;

    @BeforeEach
    void setUp() {
        reset(repository);
        cacheProperties.setAuthContextEnabled(true);
        cacheManager.getCache(CacheNames.AUTH_CONTEXT).clear();
    }

    @Test
    void load_shouldReuseDatabaseBackedSnapshot() {
        when(repository.findByTenant_IdAndUser_Id(7L, 101L))
                .thenReturn(Optional.of(member(7L, 101L, TenantMemberRole.OWNER)));

        assertThat(service.load(7L, 101L).role()).isEqualTo(TenantMemberRole.OWNER);
        assertThat(service.load(7L, 101L).role()).isEqualTo(TenantMemberRole.OWNER);

        verify(repository, times(1)).findByTenant_IdAndUser_Id(7L, 101L);
    }

    @Test
    void load_shouldKeepTenantBoundaryInCacheKey() {
        when(repository.findByTenant_IdAndUser_Id(7L, 101L))
                .thenReturn(Optional.of(member(7L, 101L, TenantMemberRole.OWNER)));
        when(repository.findByTenant_IdAndUser_Id(8L, 101L))
                .thenReturn(Optional.of(member(8L, 101L, TenantMemberRole.MEMBER)));

        assertThat(service.load(7L, 101L).role()).isEqualTo(TenantMemberRole.OWNER);
        assertThat(service.load(8L, 101L).role()).isEqualTo(TenantMemberRole.MEMBER);

        verify(repository).findByTenant_IdAndUser_Id(7L, 101L);
        verify(repository).findByTenant_IdAndUser_Id(8L, 101L);
    }

    @Test
    void evict_shouldForceFreshSecuritySnapshot() {
        when(repository.findByTenant_IdAndUser_Id(7L, 101L))
                .thenReturn(Optional.of(member(7L, 101L, TenantMemberRole.OWNER)))
                .thenReturn(Optional.of(member(7L, 101L, TenantMemberRole.MEMBER)));

        assertThat(service.load(7L, 101L).role()).isEqualTo(TenantMemberRole.OWNER);
        service.evict(7L, 101L);
        assertThat(service.load(7L, 101L).role()).isEqualTo(TenantMemberRole.MEMBER);

        verify(repository, times(2)).findByTenant_IdAndUser_Id(7L, 101L);
    }

    @Test
    void missingMembership_shouldKeepAuthenticationExceptionType() {
        when(repository.findByTenant_IdAndUser_Id(7L, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.load(7L, 404L))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication context not found");
    }

    private TenantMember member(Long tenantId, Long userId, TenantMemberRole role) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("user@example.test");
        user.setDisplayName("User");
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(SystemRole.USER);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setCode("tenant-" + tenantId);
        tenant.setName("Tenant " + tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);

        TenantMember member = new TenantMember();
        member.setUser(user);
        member.setTenant(tenant);
        member.setRole(role);
        member.setStatus(TenantMemberStatus.ACTIVE);
        return member;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            ConcurrentMapCacheManager remote = new ConcurrentMapCacheManager(CacheNames.AUTH_CONTEXT);
            TwoLevelCache authCache = new TwoLevelCache(
                    CacheNames.AUTH_CONTEXT,
                    remote.getCache(CacheNames.AUTH_CONTEXT),
                    Caffeine.newBuilder().maximumSize(100).build(),
                    true,
                    false,
                    null,
                    CacheInvalidationBus.noOp(),
                    new SimpleMeterRegistry()
            );
            return new TwoLevelCacheManager(remote, List.of(authCache));
        }

        @Bean
        CacheProperties cacheProperties() {
            return new CacheProperties();
        }

        @Bean
        TenantMemberRepository tenantMemberRepository() {
            return mock(TenantMemberRepository.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AuthenticationSnapshotService authenticationSnapshotService(
                TenantMemberRepository tenantMemberRepository,
                MeterRegistry meterRegistry
        ) {
            return new AuthenticationSnapshotService(tenantMemberRepository, meterRegistry);
        }
    }
}
