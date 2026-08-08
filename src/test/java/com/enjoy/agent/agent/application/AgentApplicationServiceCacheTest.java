package com.enjoy.agent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.api.request.UpdateAgentRequest;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.shared.config.CacheKeys;
import com.enjoy.agent.shared.config.CacheNames;
import com.enjoy.agent.shared.config.CacheProperties;
import com.enjoy.agent.shared.cache.CacheInvalidationBus;
import com.enjoy.agent.shared.cache.TwoLevelCache;
import com.enjoy.agent.shared.cache.TwoLevelCacheManager;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import java.util.Optional;
import java.util.List;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AgentApplicationServiceCacheTest.TestConfig.class)
class AgentApplicationServiceCacheTest {

    @Autowired
    private AgentApplicationService agentApplicationService;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheProperties cacheProperties;

    @BeforeEach
    void setUp() {
        reset(agentRepository, modelConfigRepository);
        cacheProperties.setAgentConfigEnabled(true);
        var cache = cacheManager.getCache(CacheNames.AGENT_CONFIG);
        if (cache != null) {
            cache.clear();
        }
        authenticateAs(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getAgent_shouldReuseCachedResponseWithinSameTenant() {
        when(agentRepository.findByIdAndTenant_Id(42L, 7L))
                .thenReturn(Optional.of(agent(42L, 7L, "租户七助手")));

        assertThat(agentApplicationService.getAgent(42L).name()).isEqualTo("租户七助手");
        assertThat(agentApplicationService.getAgent(42L).name()).isEqualTo("租户七助手");

        verify(agentRepository, times(1)).findByIdAndTenant_Id(42L, 7L);
    }

    @Test
    void getAgent_shouldNotReuseCachedResponseAcrossTenants() {
        when(agentRepository.findByIdAndTenant_Id(42L, 7L))
                .thenReturn(Optional.of(agent(42L, 7L, "租户七助手")));
        when(agentRepository.findByIdAndTenant_Id(42L, 8L)).thenReturn(Optional.empty());

        assertThat(agentApplicationService.getAgent(42L).tenantId()).isEqualTo(7L);

        authenticateAs(8L);

        assertThatThrownBy(() -> agentApplicationService.getAgent(42L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("AGENT_NOT_FOUND");
        verify(agentRepository).findByIdAndTenant_Id(42L, 8L);
    }

    @Test
    void updateAgent_shouldEvictCurrentTenantCache() {
        Agent existing = agent(42L, 7L, "旧名称");
        ModelConfig modelConfig = modelConfig(11L, "chat-model");
        when(agentRepository.findByIdAndTenant_Id(42L, 7L)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByTenant_IdAndNameAndIdNot(7L, "新名称", 42L)).thenReturn(false);
        when(modelConfigRepository.findByIdAndTenant_Id(11L, 7L)).thenReturn(Optional.of(modelConfig));
        when(agentRepository.saveAndFlush(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(agentApplicationService.getAgent(42L).name()).isEqualTo("旧名称");

        agentApplicationService.updateAgent(42L, updateRequest("新名称", 11L));

        assertThat(cacheManager.getCache(CacheNames.AGENT_CONFIG)
                .get(CacheKeys.agent(7L, 42L))).isNull();
    }

    @Test
    void deleteAgent_shouldEvictExistingEntryWhenCacheReadsAreDisabled() {
        Agent existing = agent(42L, 7L, "待删除");
        when(agentRepository.findByIdAndTenant_Id(42L, 7L)).thenReturn(Optional.of(existing));

        assertThat(agentApplicationService.getAgent(42L).name()).isEqualTo("待删除");
        cacheProperties.setAgentConfigEnabled(false);

        agentApplicationService.deleteAgent(42L);

        assertThat(cacheManager.getCache(CacheNames.AGENT_CONFIG)
                .get(CacheKeys.agent(7L, 42L))).isNull();
        verify(agentRepository).delete(existing);
    }

    private void authenticateAs(Long tenantId) {
        AuthenticatedUser currentUser = new AuthenticatedUser(
                tenantId + 100L,
                "user-" + tenantId + "@example.com",
                "user-" + tenantId,
                tenantId,
                "tenant-" + tenantId,
                "Tenant " + tenantId,
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.authorities())
        );
    }

    private Agent agent(Long agentId, Long tenantId, String name) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setTenant(tenant);
        agent.setName(name);
        agent.setDescription("desc");
        agent.setSystemPrompt("prompt");
        agent.setChatModelBindingType(AgentChatModelBindingType.USER_MODEL);
        agent.setContextStrategy(ContextStrategy.SLIDING_WINDOW);
        agent.setContextWindowSize(10);
        agent.setMemoryEnabled(false);
        agent.setMemoryUpdateMessageThreshold(6);
        agent.setEnabled(true);
        return agent;
    }

    private ModelConfig modelConfig(Long id, String name) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setId(id);
        modelConfig.setName(name);
        modelConfig.setModelType(ModelType.CHAT);
        modelConfig.setEnabled(true);
        return modelConfig;
    }

    private UpdateAgentRequest updateRequest(String name, Long modelConfigId) {
        return new UpdateAgentRequest(
                name,
                "description",
                "system prompt",
                AgentChatModelBindingType.USER_MODEL,
                modelConfigId,
                null,
                null,
                false,
                null,
                ContextStrategy.SLIDING_WINDOW,
                12,
                false,
                MemoryStrategy.SESSION_SUMMARY,
                6,
                true,
                null
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            ConcurrentMapCacheManager remote = new ConcurrentMapCacheManager(CacheNames.AGENT_CONFIG);
            TwoLevelCache agentCache = new TwoLevelCache(
                    CacheNames.AGENT_CONFIG,
                    remote.getCache(CacheNames.AGENT_CONFIG),
                    Caffeine.newBuilder().maximumSize(100).build(),
                    true,
                    false,
                    null,
                    CacheInvalidationBus.noOp(),
                    new SimpleMeterRegistry()
            );
            return new TwoLevelCacheManager(remote, List.of(agentCache));
        }

        @Bean
        CacheProperties cacheProperties() {
            return new CacheProperties();
        }

        @Bean
        AgentRepository agentRepository() {
            return mock(AgentRepository.class);
        }

        @Bean
        TenantRepository tenantRepository() {
            return mock(TenantRepository.class);
        }

        @Bean
        ModelConfigRepository modelConfigRepository() {
            return mock(ModelConfigRepository.class);
        }

        @Bean
        OfficialModelConfigRepository officialModelConfigRepository() {
            return mock(OfficialModelConfigRepository.class);
        }

        @Bean
        KnowledgeBaseRepository knowledgeBaseRepository() {
            return mock(KnowledgeBaseRepository.class);
        }

        @Bean
        AgentMcpToolBindingRepository agentMcpToolBindingRepository() {
            return mock(AgentMcpToolBindingRepository.class);
        }

        @Bean
        WorkflowRepository workflowRepository() {
            return mock(WorkflowRepository.class);
        }

        @Bean
        AgentApplicationService agentApplicationService(
                AgentRepository agentRepository,
                TenantRepository tenantRepository,
                ModelConfigRepository modelConfigRepository,
                OfficialModelConfigRepository officialModelConfigRepository,
                KnowledgeBaseRepository knowledgeBaseRepository,
                AgentMcpToolBindingRepository agentMcpToolBindingRepository,
                WorkflowRepository workflowRepository
        ) {
            return new AgentApplicationService(
                    agentRepository,
                    tenantRepository,
                    modelConfigRepository,
                    officialModelConfigRepository,
                    knowledgeBaseRepository,
                    agentMcpToolBindingRepository,
                    workflowRepository
            );
        }
    }
}
