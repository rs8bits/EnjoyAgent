package com.enjoy.agent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.api.request.CreateAgentRequest;
import com.enjoy.agent.agent.api.request.UpdateAgentRequest;
import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AgentApplicationServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private OfficialModelConfigRepository officialModelConfigRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private AgentMcpToolBindingRepository agentMcpToolBindingRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    private AgentApplicationService agentApplicationService;

    @BeforeEach
    void setUp() {
        agentApplicationService = new AgentApplicationService(
                agentRepository,
                tenantRepository,
                modelConfigRepository,
                officialModelConfigRepository,
                knowledgeBaseRepository,
                agentMcpToolBindingRepository,
                workflowRepository
        );
        AuthenticatedUser currentUser = new AuthenticatedUser(
                11L,
                "tester@example.com",
                "tester",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.authorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAgents_shouldSearchWithinTenantWithoutLoadingEveryPage() {
        var pageable = PageRequest.of(3, 20);
        when(agentRepository.findAllByTenant_IdAndNameContainingIgnoreCase(7L, "知识助手", pageable))
                .thenReturn(Page.empty(pageable));

        var result = agentApplicationService.listAgents(pageable, "  知识助手  ");

        assertThat(result).isEmpty();
        verify(agentRepository).findAllByTenant_IdAndNameContainingIgnoreCase(7L, "知识助手", pageable);
    }

    @Test
    void createAgent_shouldRejectRerankWithoutKnowledgeBase() {
        when(agentRepository.existsByTenant_IdAndName(7L, "知识助手")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));
        when(modelConfigRepository.findByIdAndTenant_Id(101L, 7L)).thenReturn(Optional.of(chatModel(101L)));

        assertThatThrownBy(() -> agentApplicationService.createAgent(new CreateAgentRequest(
                "知识助手",
                "desc",
                "prompt",
                AgentChatModelBindingType.USER_MODEL,
                101L,
                null,
                null,
                true,
                301L,
                ContextStrategy.SLIDING_WINDOW,
                12,
                false,
                null,
                null,
                true
        )))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("AGENT_RERANK_REQUIRES_KNOWLEDGE_BASE");
    }

    @Test
    void createAgent_shouldRejectNonRerankModelWhenRerankEnabled() {
        when(agentRepository.existsByTenant_IdAndName(7L, "知识助手")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));
        when(modelConfigRepository.findByIdAndTenant_Id(101L, 7L)).thenReturn(Optional.of(chatModel(101L)));
        when(knowledgeBaseRepository.findByIdAndTenant_Id(201L, 7L)).thenReturn(Optional.of(enabledKnowledgeBase()));
        when(modelConfigRepository.findByIdAndTenant_Id(301L, 7L)).thenReturn(Optional.of(chatModel(301L)));

        assertThatThrownBy(() -> agentApplicationService.createAgent(new CreateAgentRequest(
                "知识助手",
                "desc",
                "prompt",
                AgentChatModelBindingType.USER_MODEL,
                101L,
                null,
                201L,
                true,
                301L,
                ContextStrategy.SLIDING_WINDOW,
                12,
                false,
                null,
                null,
                true
        )))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MODEL_CONFIG_TYPE_INVALID");
    }

    @Test
    void createAgent_shouldBindRerankModelWhenEnabled() {
        when(agentRepository.existsByTenant_IdAndName(7L, "知识助手")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));
        when(modelConfigRepository.findByIdAndTenant_Id(101L, 7L)).thenReturn(Optional.of(chatModel(101L)));
        when(knowledgeBaseRepository.findByIdAndTenant_Id(201L, 7L)).thenReturn(Optional.of(enabledKnowledgeBase()));
        when(modelConfigRepository.findByIdAndTenant_Id(301L, 7L)).thenReturn(Optional.of(rerankModel(301L)));
        when(agentRepository.saveAndFlush(any(Agent.class))).thenAnswer(invocation -> {
            Agent agent = invocation.getArgument(0);
            agent.setId(901L);
            return agent;
        });

        var response = agentApplicationService.createAgent(new CreateAgentRequest(
                "知识助手",
                "desc",
                "prompt",
                AgentChatModelBindingType.USER_MODEL,
                101L,
                null,
                201L,
                true,
                301L,
                ContextStrategy.SLIDING_WINDOW,
                12,
                true,
                MemoryStrategy.SESSION_SUMMARY,
                6,
                true
        ));

        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).saveAndFlush(agentCaptor.capture());
        Agent savedAgent = agentCaptor.getValue();
        assertThat(savedAgent.isRerankEnabled()).isTrue();
        assertThat(savedAgent.getRerankModelConfig()).isNotNull();
        assertThat(savedAgent.getRerankModelConfig().getId()).isEqualTo(301L);
        assertThat(savedAgent.isMemoryEnabled()).isTrue();
        assertThat(savedAgent.getMemoryStrategy()).isEqualTo(MemoryStrategy.SESSION_SUMMARY);
        assertThat(savedAgent.getMemoryUpdateMessageThreshold()).isEqualTo(6);

        assertThat(response.rerankEnabled()).isTrue();
        assertThat(response.rerankModelConfigId()).isEqualTo(301L);
        assertThat(response.rerankModelConfigName()).isEqualTo("知识重排");
        assertThat(response.memoryEnabled()).isTrue();
        assertThat(response.memoryStrategy()).isEqualTo("SESSION_SUMMARY");
        assertThat(response.memoryUpdateMessageThreshold()).isEqualTo(6);
    }

    @Test
    void updateAgent_shouldReturnTheSelectedWorkflowBinding() {
        Agent existing = configuredAgent(901L);
        Workflow workflow = new Workflow();
        workflow.setId(501L);
        workflow.setTenant(activeTenant());
        workflow.setName("售后编排");
        workflow.setEnabled(true);

        when(agentRepository.findByIdAndTenant_Id(901L, 7L)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByTenant_IdAndNameAndIdNot(7L, "工作流助手", 901L)).thenReturn(false);
        when(modelConfigRepository.findByIdAndTenant_Id(101L, 7L)).thenReturn(Optional.of(chatModel(101L)));
        when(workflowRepository.findByIdAndTenant_Id(501L, 7L)).thenReturn(Optional.of(workflow));
        when(agentRepository.saveAndFlush(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = agentApplicationService.updateAgent(901L, new UpdateAgentRequest(
                "工作流助手",
                "desc",
                "prompt",
                AgentChatModelBindingType.USER_MODEL,
                101L,
                null,
                null,
                false,
                null,
                ContextStrategy.SLIDING_WINDOW,
                12,
                false,
                null,
                null,
                true,
                501L
        ));

        assertThat(existing.getWorkflow()).isSameAs(workflow);
        assertThat(response.workflowId()).isEqualTo(501L);
        assertThat(response.workflowName()).isEqualTo("售后编排");
    }

    private Tenant activeTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setCode("tenant-7");
        tenant.setName("Tenant 7");
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenant;
    }

    private Agent configuredAgent(Long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setTenant(activeTenant());
        agent.setName("旧名称");
        agent.setDescription("desc");
        agent.setSystemPrompt("prompt");
        agent.setChatModelBindingType(AgentChatModelBindingType.USER_MODEL);
        agent.setContextStrategy(ContextStrategy.SLIDING_WINDOW);
        agent.setContextWindowSize(12);
        agent.setMemoryEnabled(false);
        agent.setMemoryUpdateMessageThreshold(6);
        agent.setEnabled(true);
        return agent;
    }

    private KnowledgeBase enabledKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(201L);
        knowledgeBase.setName("员工手册");
        knowledgeBase.setEnabled(true);
        knowledgeBase.setEmbeddingModelConfig(embeddingModel(401L));
        return knowledgeBase;
    }

    private ModelConfig chatModel(Long id) {
        return modelConfig(id, "默认对话", ModelType.CHAT);
    }

    private ModelConfig rerankModel(Long id) {
        return modelConfig(id, "知识重排", ModelType.RERANK);
    }

    private ModelConfig embeddingModel(Long id) {
        return modelConfig(id, "向量模型", ModelType.EMBEDDING);
    }

    private ModelConfig modelConfig(Long id, String name, ModelType type) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setId(id);
        modelConfig.setName(name);
        modelConfig.setProvider(CredentialProvider.OPENAI);
        modelConfig.setModelType(type);
        modelConfig.setModelName(name + "-model");
        modelConfig.setCredentialSource(ModelCredentialSource.USER);
        modelConfig.setEnabled(true);
        return modelConfig;
    }
}
