package com.enjoy.agent.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.application.KnowledgeDocumentApplicationService;
import com.enjoy.agent.knowledge.application.MinioStorageService;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.market.api.request.InstallMarketAssetRequest;
import com.enjoy.agent.market.api.request.SubmitMarketAssetRequest;
import com.enjoy.agent.market.api.response.MarketAssetInstallResponse;
import com.enjoy.agent.market.api.response.MarketAssetResponse;
import com.enjoy.agent.market.domain.entity.MarketAsset;
import com.enjoy.agent.market.domain.entity.MarketAssetInstall;
import com.enjoy.agent.market.domain.enums.MarketAssetStatus;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowEdgeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import com.enjoy.agent.market.domain.enums.MarketAssetType;
import com.enjoy.agent.market.infrastructure.persistence.MarketAssetInstallRepository;
import com.enjoy.agent.market.infrastructure.persistence.MarketAssetRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpServerRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class MarketAssetApplicationServiceTest {

    @Mock
    private MarketAssetRepository marketAssetRepository;
    @Mock
    private MarketAssetInstallRepository marketAssetInstallRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;
    @Mock
    private McpServerRepository mcpServerRepository;
    @Mock
    private McpToolRepository mcpToolRepository;
    @Mock
    private AgentMcpToolBindingRepository agentMcpToolBindingRepository;
    @Mock
    private ModelConfigRepository modelConfigRepository;
    @Mock
    private OfficialModelConfigRepository officialModelConfigRepository;
    @Mock
    private CredentialRepository credentialRepository;
    @Mock
    private KnowledgeDocumentApplicationService knowledgeDocumentApplicationService;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowNodeRepository workflowNodeRepository;
    @Mock
    private WorkflowEdgeRepository workflowEdgeRepository;

    private MarketAssetApplicationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MarketAssetApplicationService(
                marketAssetRepository,
                marketAssetInstallRepository,
                agentRepository,
                knowledgeBaseRepository,
                knowledgeDocumentRepository,
                mcpServerRepository,
                mcpToolRepository,
                agentMcpToolBindingRepository,
                modelConfigRepository,
                officialModelConfigRepository,
                credentialRepository,
                knowledgeDocumentApplicationService,
                minioStorageService,
                tenantRepository,
                appUserRepository,
                workflowRepository,
                workflowNodeRepository,
                workflowEdgeRepository,
                objectMapper,
                Clock.fixed(Instant.parse("2026-04-09T01:00:00Z"), ZoneOffset.UTC)
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
    void submitAgentAsset_shouldCreatePendingSnapshot() {
        Agent agent = sourceAgent();
        AppUser user = currentUserEntity();
        when(agentRepository.findByIdAndTenant_Id(101L, 7L)).thenReturn(Optional.of(agent));
        when(agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(101L)).thenReturn(List.of());
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(marketAssetRepository.findByAssetTypeAndSourceTenantIdAndSourceEntityId(MarketAssetType.AGENT, 7L, 101L))
                .thenReturn(Optional.empty());
        when(marketAssetRepository.save(any(MarketAsset.class))).thenAnswer(invocation -> {
            MarketAsset asset = invocation.getArgument(0);
            asset.setId(1L);
            return asset;
        });

        MarketAssetResponse response = service.submitAgentAsset(
                101L,
                new SubmitMarketAssetRequest("适合知识问答", "安装后请自行绑定知识库")
        );

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.assetType()).isEqualTo("AGENT");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.name()).isEqualTo("产品助手");
        assertThat(response.submitterUserId()).isEqualTo(11L);
    }

    @Test
    void installAgentAsset_shouldCreateTenantCopyFromApprovedAsset() throws Exception {
        AppUser user = currentUserEntity();
        Tenant tenant = activeTenant();
        AgentMarketSnapshot snapshot = new AgentMarketSnapshot(
                "产品助手模板",
                "共享的产品助手模板",
                "你是产品助手",
                AgentChatModelBindingType.USER_MODEL,
                null,
                false,
                ContextStrategy.SLIDING_WINDOW,
                12,
                true,
                com.enjoy.agent.agent.domain.enums.MemoryStrategy.SESSION_SUMMARY,
                6,
                true,
                null,
                null,
                null,
                List.of()
        );

        MarketAsset asset = new MarketAsset();
        asset.setId(1L);
        asset.setAssetType(MarketAssetType.AGENT);
        asset.setStatus(MarketAssetStatus.APPROVED);
        asset.setName("产品助手模板");
        asset.setSourceTenantId(7L);
        asset.setSourceEntityId(101L);
        asset.setSubmitterUser(user);
        asset.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        asset.setInstallCount(0);

        ModelConfig chatModel = new ModelConfig();
        chatModel.setId(201L);
        chatModel.setName("我的聊天模型");
        chatModel.setModelType(ModelType.CHAT);
        chatModel.setCredentialSource(ModelCredentialSource.USER);
        chatModel.setEnabled(true);

        when(marketAssetRepository.findById(1L)).thenReturn(Optional.of(asset));
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(tenant));
        when(modelConfigRepository.findByIdAndTenant_Id(201L, 7L)).thenReturn(Optional.of(chatModel));
        when(agentRepository.existsByTenant_IdAndName(7L, "产品助手模板")).thenReturn(false);
        when(agentRepository.saveAndFlush(any(Agent.class))).thenAnswer(invocation -> {
            Agent saved = invocation.getArgument(0);
            saved.setId(501L);
            return saved;
        });
        when(marketAssetRepository.save(any(MarketAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketAssetInstallRepository.save(any(MarketAssetInstall.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketAssetInstallResponse response = service.installAsset(
                1L,
                new InstallMarketAssetRequest(
                        null,
                        201L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true
                )
        );

        assertThat(response.marketAssetId()).isEqualTo(1L);
        assertThat(response.installedEntityId()).isEqualTo(501L);
        assertThat(response.installedName()).isEqualTo("产品助手模板");
        assertThat(response.relatedResources()).isEmpty();
        assertThat(response.setupRequired()).isFalse();
        assertThat(response.setupItems()).isEmpty();

        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).saveAndFlush(agentCaptor.capture());
        Agent installedAgent = agentCaptor.getValue();
        assertThat(installedAgent.getTenant().getId()).isEqualTo(7L);
        assertThat(installedAgent.getModelConfig().getId()).isEqualTo(201L);
        assertThat(installedAgent.getChatModelBindingType()).isEqualTo(AgentChatModelBindingType.USER_MODEL);
        assertThat(installedAgent.getSystemPrompt()).isEqualTo("你是产品助手");
    }

    @Test
    void submitWorkflowAsset_shouldSnapshotEdgesByNodeId() throws Exception {
        Workflow workflow = sourceWorkflow();
        AppUser user = currentUserEntity();
        WorkflowNode startNode = workflowNode(1001L, workflow, "开始", WorkflowNodeType.START, "{}");
        WorkflowNode llmNode = workflowNode(1002L, workflow, "LLM", WorkflowNodeType.LLM, "{\"modelConfigId\":201}");

        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflow(workflow);
        edge.setSourceNode(workflowNode(1001L, workflow, "开始引用", WorkflowNodeType.START, "{}"));
        edge.setTargetNode(workflowNode(1002L, workflow, "LLM 引用", WorkflowNodeType.LLM, "{}"));
        edge.setSourceHandle("source");
        edge.setTargetHandle("target");

        when(workflowRepository.findByIdAndTenant_Id(301L, 7L)).thenReturn(Optional.of(workflow));
        when(workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(301L)).thenReturn(List.of(startNode, llmNode));
        when(workflowEdgeRepository.findAllByWorkflow_Id(301L)).thenReturn(List.of(edge));
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(marketAssetRepository.findByAssetTypeAndSourceTenantIdAndSourceEntityId(MarketAssetType.WORKFLOW, 7L, 301L))
                .thenReturn(Optional.empty());
        when(marketAssetRepository.save(any(MarketAsset.class))).thenAnswer(invocation -> {
            MarketAsset asset = invocation.getArgument(0);
            asset.setId(3L);
            return asset;
        });

        MarketAssetResponse response = service.submitWorkflowAsset(
                301L,
                new SubmitMarketAssetRequest("可复用工作流", "包含开始、LLM 和连线")
        );

        assertThat(response.assetType()).isEqualTo("WORKFLOW");
        ArgumentCaptor<MarketAsset> assetCaptor = ArgumentCaptor.forClass(MarketAsset.class);
        verify(marketAssetRepository).save(assetCaptor.capture());
        WorkflowMarketSnapshot snapshot = objectMapper.readValue(
                assetCaptor.getValue().getSnapshotJson(),
                WorkflowMarketSnapshot.class
        );
        assertThat(snapshot.name()).isEqualTo("客户问答流程");
        assertThat(objectMapper.readTree(snapshot.nodesJson())).hasSize(2);
        assertThat(objectMapper.readTree(snapshot.edgesJson()).get(0).path("sourceNodeIndex").asInt()).isZero();
        assertThat(objectMapper.readTree(snapshot.edgesJson()).get(0).path("targetNodeIndex").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(snapshot.edgesJson()).get(0).path("sourceHandle").asText()).isEqualTo("source");
    }

    @Test
    void installWorkflowAsset_shouldCopyCanvasAndRemapTenantDependencies() throws Exception {
        AppUser user = currentUserEntity();
        Tenant tenant = activeTenant();
        String nodesJson = objectMapper.writeValueAsString(List.of(
                Map.of("name", "开始", "nodeType", "START", "configJson", "{}", "positionX", 0, "positionY", 0),
                Map.of("name", "知识检索", "nodeType", "KNOWLEDGE", "configJson", "{\"knowledgeBaseId\":9001}", "positionX", 200, "positionY", 0),
                Map.of("name", "LLM", "nodeType", "LLM", "configJson", "{\"modelConfigId\":9002,\"temperature\":0.2}", "positionX", 400, "positionY", 0),
                Map.of("name", "工具调用", "nodeType", "TOOL", "configJson", "{\"toolId\":9003,\"toolName\":\"search\"}", "positionX", 600, "positionY", 0),
                Map.of("name", "结束", "nodeType", "END", "configJson", "{}", "positionX", 800, "positionY", 0)
        ));
        String edgesJson = objectMapper.writeValueAsString(List.of(
                Map.of("sourceNodeIndex", 0, "targetNodeIndex", 1),
                Map.of("sourceNodeIndex", 1, "targetNodeIndex", 2),
                Map.of("sourceNodeIndex", 2, "targetNodeIndex", 3),
                Map.of("sourceNodeIndex", 3, "targetNodeIndex", 4)
        ));
        WorkflowMarketSnapshot snapshot = new WorkflowMarketSnapshot(
                "客户问答流程",
                "共享流程",
                true,
                nodesJson,
                edgesJson
        );

        MarketAsset asset = new MarketAsset();
        asset.setId(4L);
        asset.setAssetType(MarketAssetType.WORKFLOW);
        asset.setStatus(MarketAssetStatus.APPROVED);
        asset.setName("客户问答流程");
        asset.setSourceTenantId(99L);
        asset.setSourceEntityId(301L);
        asset.setSubmitterUser(user);
        asset.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        asset.setInstallCount(0);

        ModelConfig chatModel = new ModelConfig();
        chatModel.setId(201L);
        chatModel.setName("聊天模型");
        chatModel.setModelType(ModelType.CHAT);
        chatModel.setCredentialSource(ModelCredentialSource.USER);
        chatModel.setEnabled(true);

        KnowledgeBase targetKnowledgeBase = new KnowledgeBase();
        targetKnowledgeBase.setId(401L);
        targetKnowledgeBase.setTenant(tenant);
        targetKnowledgeBase.setName("目标知识库");
        targetKnowledgeBase.setEnabled(true);

        when(marketAssetRepository.findById(4L)).thenReturn(Optional.of(asset));
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(tenant));
        when(workflowRepository.existsByTenant_IdAndName(7L, "安装后的工作流")).thenReturn(false);
        when(workflowRepository.saveAndFlush(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow saved = invocation.getArgument(0);
            saved.setId(801L);
            return saved;
        });
        when(workflowNodeRepository.save(any(WorkflowNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigRepository.findByIdAndTenant_Id(201L, 7L)).thenReturn(Optional.of(chatModel));
        when(knowledgeBaseRepository.findByIdAndTenant_Id(401L, 7L)).thenReturn(Optional.of(targetKnowledgeBase));
        when(marketAssetRepository.save(any(MarketAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketAssetInstallRepository.save(any(MarketAssetInstall.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketAssetInstallResponse response = service.installAsset(
                4L,
                new InstallMarketAssetRequest(
                        "安装后的工作流",
                        201L,
                        null,
                        401L,
                        null,
                        null,
                        null,
                        true
                )
        );

        assertThat(response.assetType()).isEqualTo("WORKFLOW");
        assertThat(response.installedEntityId()).isEqualTo(801L);
        assertThat(response.installedName()).isEqualTo("安装后的工作流");
        assertThat(response.setupRequired()).isTrue();
        assertThat(response.setupItems()).contains("工作流工具节点需要重新选择当前租户的 MCP 工具，或绑定到拥有同名工具的 Agent。");

        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeRepository, times(5)).save(nodeCaptor.capture());
        WorkflowNode installedKnowledgeNode = nodeCaptor.getAllValues().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.KNOWLEDGE)
                .findFirst()
                .orElseThrow();
        WorkflowNode installedLlmNode = nodeCaptor.getAllValues().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.LLM)
                .findFirst()
                .orElseThrow();
        WorkflowNode installedToolNode = nodeCaptor.getAllValues().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.TOOL)
                .findFirst()
                .orElseThrow();

        assertThat(objectMapper.readTree(installedKnowledgeNode.getConfigJson()).path("knowledgeBaseId").asLong()).isEqualTo(401L);
        assertThat(objectMapper.readTree(installedLlmNode.getConfigJson()).path("modelConfigId").asLong()).isEqualTo(201L);
        assertThat(objectMapper.readTree(installedToolNode.getConfigJson()).has("toolId")).isFalse();
        assertThat(objectMapper.readTree(installedToolNode.getConfigJson()).path("toolName").asText()).isEqualTo("search");
        verify(workflowEdgeRepository, times(4)).save(any(WorkflowEdge.class));
    }

    @Test
    void installAgentAsset_shouldCopyPackagedKnowledgeBase() throws Exception {
        AppUser user = currentUserEntity();
        Tenant tenant = activeTenant();
        AgentMarketSnapshot snapshot = new AgentMarketSnapshot(
                "知识助手模板",
                "共享的知识助手模板",
                "你是知识助手",
                AgentChatModelBindingType.USER_MODEL,
                null,
                false,
                ContextStrategy.SLIDING_WINDOW,
                8,
                false,
                null,
                6,
                true,
                new KnowledgeBaseMarketSnapshot(
                        "产品知识库",
                        "产品文档副本",
                        true,
                        List.of(new KnowledgeDocumentMarketSnapshot(
                                "spec.txt",
                                "text/plain",
                                12L,
                                "bucket-a",
                                "market/doc-1"
                        ))
                ),
                null,
                "产品知识库",
                List.of()
        );

        MarketAsset asset = new MarketAsset();
        asset.setId(2L);
        asset.setAssetType(MarketAssetType.AGENT);
        asset.setStatus(MarketAssetStatus.APPROVED);
        asset.setName("知识助手模板");
        asset.setSourceTenantId(7L);
        asset.setSourceEntityId(102L);
        asset.setSubmitterUser(user);
        asset.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        asset.setInstallCount(0);

        ModelConfig chatModel = new ModelConfig();
        chatModel.setId(201L);
        chatModel.setName("聊天模型");
        chatModel.setModelType(ModelType.CHAT);
        chatModel.setCredentialSource(ModelCredentialSource.USER);
        chatModel.setEnabled(true);

        ModelConfig embeddingModel = new ModelConfig();
        embeddingModel.setId(401L);
        embeddingModel.setName("embedding");
        embeddingModel.setModelType(ModelType.EMBEDDING);
        embeddingModel.setCredentialSource(ModelCredentialSource.USER);
        embeddingModel.setEnabled(true);

        when(marketAssetRepository.findById(2L)).thenReturn(Optional.of(asset));
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(tenant));
        when(modelConfigRepository.findByIdAndTenant_Id(201L, 7L)).thenReturn(Optional.of(chatModel));
        when(modelConfigRepository.findByIdAndTenant_Id(401L, 7L)).thenReturn(Optional.of(embeddingModel));
        when(agentRepository.existsByTenant_IdAndName(7L, "知识助手模板")).thenReturn(false);
        when(knowledgeBaseRepository.existsByTenant_IdAndName(7L, "产品知识库")).thenReturn(false);
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase kb = invocation.getArgument(0);
            kb.setId(601L);
            return kb;
        });
        when(agentRepository.saveAndFlush(any(Agent.class))).thenAnswer(invocation -> {
            Agent saved = invocation.getArgument(0);
            saved.setId(701L);
            return saved;
        });
        when(minioStorageService.downloadObject("bucket-a", "market/doc-1")).thenReturn("hello world".getBytes());
        when(marketAssetRepository.save(any(MarketAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketAssetInstallRepository.save(any(MarketAssetInstall.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketAssetInstallResponse response = service.installAsset(
                2L,
                new InstallMarketAssetRequest(
                        null,
                        201L,
                        null,
                        null,
                        null,
                        401L,
                        null,
                        true
                )
        );

        assertThat(response.installedEntityId()).isEqualTo(701L);
        assertThat(response.relatedResources()).hasSize(1);
        assertThat(response.relatedResources().getFirst().resourceType()).isEqualTo("KNOWLEDGE_BASE");
        assertThat(response.relatedResources().getFirst().id()).isEqualTo(601L);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(knowledgeDocumentApplicationService).uploadDocumentFromBytes(
                org.mockito.ArgumentMatchers.eq(601L),
                org.mockito.ArgumentMatchers.eq("spec.txt"),
                org.mockito.ArgumentMatchers.eq("text/plain"),
                bytesCaptor.capture()
        );
        assertThat(bytesCaptor.getValue()).isEqualTo("hello world".getBytes());
    }

    private Agent sourceAgent() {
        Agent agent = new Agent();
        agent.setId(101L);
        agent.setTenant(activeTenant());
        agent.setName("产品助手");
        agent.setDescription("帮助回答产品问题");
        agent.setSystemPrompt("你是一个产品助手");
        agent.setChatModelBindingType(AgentChatModelBindingType.USER_MODEL);
        agent.setContextStrategy(ContextStrategy.SLIDING_WINDOW);
        agent.setContextWindowSize(12);
        agent.setMemoryEnabled(true);
        agent.setMemoryStrategy(com.enjoy.agent.agent.domain.enums.MemoryStrategy.SESSION_SUMMARY);
        agent.setMemoryUpdateMessageThreshold(6);
        agent.setEnabled(true);
        return agent;
    }

    private Workflow sourceWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(301L);
        workflow.setTenant(activeTenant());
        workflow.setName("客户问答流程");
        workflow.setDescription("共享流程");
        workflow.setEnabled(true);
        return workflow;
    }

    private WorkflowNode workflowNode(Long id, Workflow workflow, String name, WorkflowNodeType nodeType, String configJson) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setWorkflow(workflow);
        node.setName(name);
        node.setNodeType(nodeType);
        node.setConfigJson(configJson);
        node.setPositionX(id.intValue());
        node.setPositionY(id.intValue());
        return node;
    }

    private AppUser currentUserEntity() {
        AppUser user = new AppUser();
        user.setId(11L);
        user.setEmail("tester@example.com");
        user.setDisplayName("tester");
        user.setSystemRole(SystemRole.USER);
        return user;
    }

    private Tenant activeTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setCode("tenant-7");
        tenant.setName("Tenant 7");
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenant;
    }
}
