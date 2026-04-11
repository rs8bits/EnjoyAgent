package com.enjoy.agent.market.application;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus;
import com.enjoy.agent.knowledge.application.KnowledgeDocumentApplicationService;
import com.enjoy.agent.knowledge.application.MinioStorageService;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.market.api.request.InstallMarketAssetRequest;
import com.enjoy.agent.market.api.request.ReviewMarketAssetRequest;
import com.enjoy.agent.market.api.request.SubmitMarketAssetRequest;
import com.enjoy.agent.market.api.response.MarketAssetInstallResponse;
import com.enjoy.agent.market.api.response.MarketInstalledResourceResponse;
import com.enjoy.agent.market.api.response.MarketAssetResponse;
import com.enjoy.agent.market.domain.entity.MarketAsset;
import com.enjoy.agent.market.domain.entity.MarketAssetInstall;
import com.enjoy.agent.market.domain.enums.MarketAssetStatus;
import com.enjoy.agent.market.domain.enums.MarketAssetType;
import com.enjoy.agent.market.infrastructure.persistence.MarketAssetInstallRepository;
import com.enjoy.agent.market.infrastructure.persistence.MarketAssetRepository;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpServerRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 共享市场和管理端审核应用服务。
 */
@Service
public class MarketAssetApplicationService {

    private final MarketAssetRepository marketAssetRepository;
    private final MarketAssetInstallRepository marketAssetInstallRepository;
    private final AgentRepository agentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final OfficialModelConfigRepository officialModelConfigRepository;
    private final CredentialRepository credentialRepository;
    private final KnowledgeDocumentApplicationService knowledgeDocumentApplicationService;
    private final MinioStorageService minioStorageService;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketAssetApplicationService(
            MarketAssetRepository marketAssetRepository,
            MarketAssetInstallRepository marketAssetInstallRepository,
            AgentRepository agentRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            McpServerRepository mcpServerRepository,
            McpToolRepository mcpToolRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository,
            ModelConfigRepository modelConfigRepository,
            OfficialModelConfigRepository officialModelConfigRepository,
            CredentialRepository credentialRepository,
            KnowledgeDocumentApplicationService knowledgeDocumentApplicationService,
            MinioStorageService minioStorageService,
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.marketAssetRepository = marketAssetRepository;
        this.marketAssetInstallRepository = marketAssetInstallRepository;
        this.agentRepository = agentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolRepository = mcpToolRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.officialModelConfigRepository = officialModelConfigRepository;
        this.credentialRepository = credentialRepository;
        this.knowledgeDocumentApplicationService = knowledgeDocumentApplicationService;
        this.minioStorageService = minioStorageService;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public MarketAssetResponse submitAgentAsset(Long agentId, SubmitMarketAssetRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Agent agent = agentRepository.findByIdAndTenant_Id(agentId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("AGENT_NOT_FOUND", "Agent not found", HttpStatus.NOT_FOUND));
        List<AgentMcpToolBinding> bindings = agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(agent.getId());
        List<String> toolNames = bindings.stream()
                .filter(AgentMcpToolBinding::isEnabled)
                .map(binding -> binding.getTool().getName())
                .distinct()
                .toList();
        KnowledgeBaseMarketSnapshot knowledgeBaseSnapshot = agent.getKnowledgeBase() == null
                ? null
                : buildKnowledgeBaseSnapshot(agent.getKnowledgeBase(), "agent/%s/knowledge-base".formatted(agent.getId()));
        AgentMarketSnapshot snapshot = new AgentMarketSnapshot(
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                agent.getChatModelBindingType() == null ? AgentChatModelBindingType.USER_MODEL : agent.getChatModelBindingType(),
                agent.getOfficialModelConfig() == null ? null : agent.getOfficialModelConfig().getId(),
                agent.isRerankEnabled(),
                agent.getContextStrategy(),
                agent.getContextWindowSize(),
                agent.isMemoryEnabled(),
                agent.getMemoryStrategy() == null ? MemoryStrategy.SESSION_SUMMARY : agent.getMemoryStrategy(),
                agent.getMemoryUpdateMessageThreshold(),
                agent.isEnabled(),
                knowledgeBaseSnapshot,
                buildMcpServerSnapshots(bindings),
                agent.getKnowledgeBase() == null ? null : agent.getKnowledgeBase().getName(),
                toolNames
        );
        return upsertAsset(
                MarketAssetType.AGENT,
                currentUser,
                agent.getId(),
                agent.getName(),
                request,
                snapshot
        );
    }

    @Transactional
    public MarketAssetResponse submitKnowledgeBaseAsset(Long knowledgeBaseId, SubmitMarketAssetRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
        KnowledgeBaseMarketSnapshot snapshot = buildKnowledgeBaseSnapshot(
                knowledgeBase,
                "knowledge-base/%s".formatted(knowledgeBase.getId())
        );
        return upsertAsset(
                MarketAssetType.KNOWLEDGE_BASE,
                currentUser,
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                request,
                snapshot
        );
    }

    @Transactional
    public MarketAssetResponse submitMcpServerAsset(Long serverId, SubmitMarketAssetRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        McpServer server = mcpServerRepository.findByIdAndTenant_Id(serverId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("MCP_SERVER_NOT_FOUND", "MCP server not found", HttpStatus.NOT_FOUND));
        List<McpToolMarketSnapshot> tools = mcpToolRepository.findAllByServer_IdOrderByIdAsc(server.getId()).stream()
                .map(tool -> new McpToolMarketSnapshot(
                        tool.getName(),
                        tool.getDescription(),
                        tool.getInputSchemaJson(),
                        tool.getRiskLevel(),
                        tool.isEnabled()
                ))
                .toList();
        McpServerMarketSnapshot snapshot = new McpServerMarketSnapshot(
                server.getName(),
                server.getDescription(),
                server.getBaseUrl(),
                server.getTransportType(),
                server.getAuthType(),
                server.isEnabled(),
                tools
        );
        return upsertAsset(
                MarketAssetType.MCP_SERVER,
                currentUser,
                server.getId(),
                server.getName(),
                request,
                snapshot
        );
    }

    @Transactional(readOnly = true)
    public List<MarketAssetResponse> listCurrentUserSubmissions() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return marketAssetRepository.findAllBySubmitterUser_IdOrderByIdDesc(currentUser.userId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketAssetResponse> listPublishedAssets(MarketAssetType assetType) {
        List<MarketAsset> assets = assetType == null
                ? marketAssetRepository.findAllByStatusOrderByPublishedAtDescIdDesc(MarketAssetStatus.APPROVED)
                : marketAssetRepository.findAllByStatusAndAssetTypeOrderByPublishedAtDescIdDesc(MarketAssetStatus.APPROVED, assetType);
        return assets.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MarketAssetResponse getAsset(Long id) {
        MarketAsset asset = requireVisibleAsset(id);
        return toResponse(asset);
    }

    @Transactional
    public MarketAssetInstallResponse installAsset(Long id, InstallMarketAssetRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        AppUser installer = requireCurrentUserEntity(currentUser.userId());
        Tenant tenant = requireActiveTenant(currentUser.tenantId());
        MarketAsset asset = requireApprovedAsset(id);

        InstallationResult result = switch (asset.getAssetType()) {
            case AGENT -> installAgentAsset(asset, currentUser, tenant, request);
            case KNOWLEDGE_BASE -> installKnowledgeBaseAsset(asset, tenant, request);
            case MCP_SERVER -> installMcpServerAsset(asset, currentUser, tenant, request);
        };

        asset.setInstallCount(asset.getInstallCount() == null ? 1 : asset.getInstallCount() + 1);
        marketAssetRepository.save(asset);

        MarketAssetInstall install = new MarketAssetInstall();
        install.setMarketAsset(asset);
        install.setInstallerUser(installer);
        install.setTenant(tenant);
        install.setInstalledEntityId(result.installedEntityId());
        install.setInstalledName(result.installedName());
        marketAssetInstallRepository.save(install);

        return new MarketAssetInstallResponse(
                asset.getId(),
                asset.getAssetType().name(),
                result.installedEntityId(),
                result.installedName(),
                result.relatedResources(),
                !result.setupItems().isEmpty(),
                result.setupItems()
        );
    }

    @Transactional(readOnly = true)
    public List<MarketAssetResponse> listAdminAssets(MarketAssetType assetType, MarketAssetStatus status) {
        requireAdmin();
        List<MarketAsset> assets;
        if (assetType == null && status == null) {
            assets = marketAssetRepository.findAllByOrderByIdDesc();
        } else if (assetType == null) {
            assets = marketAssetRepository.findAllByStatusOrderByIdDesc(status);
        } else if (status == null) {
            assets = marketAssetRepository.findAllByAssetTypeOrderByIdDesc(assetType);
        } else {
            assets = marketAssetRepository.findAllByStatusAndAssetTypeOrderByIdDesc(status, assetType);
        }
        return assets.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MarketAssetResponse approveAsset(Long id, ReviewMarketAssetRequest request) {
        AppUser reviewer = requireAdminUser();
        MarketAsset asset = requireAsset(id);
        asset.setStatus(MarketAssetStatus.APPROVED);
        asset.setReviewedBy(reviewer);
        asset.setReviewedAt(Instant.now(clock));
        asset.setReviewRemark(normalizeReviewRemark(request.reviewRemark()));
        asset.setPublishedAt(Instant.now(clock));
        return toResponse(marketAssetRepository.save(asset));
    }

    @Transactional
    public MarketAssetResponse rejectAsset(Long id, ReviewMarketAssetRequest request) {
        AppUser reviewer = requireAdminUser();
        MarketAsset asset = requireAsset(id);
        asset.setStatus(MarketAssetStatus.REJECTED);
        asset.setReviewedBy(reviewer);
        asset.setReviewedAt(Instant.now(clock));
        asset.setReviewRemark(normalizeReviewRemark(request.reviewRemark()));
        asset.setPublishedAt(null);
        return toResponse(marketAssetRepository.save(asset));
    }

    @Transactional
    public MarketAssetResponse offlineAsset(Long id, ReviewMarketAssetRequest request) {
        AppUser reviewer = requireAdminUser();
        MarketAsset asset = requireAsset(id);
        asset.setStatus(MarketAssetStatus.OFFLINE);
        asset.setReviewedBy(reviewer);
        asset.setReviewedAt(Instant.now(clock));
        asset.setReviewRemark(normalizeReviewRemark(request.reviewRemark()));
        return toResponse(marketAssetRepository.save(asset));
    }

    private MarketAssetResponse upsertAsset(
            MarketAssetType assetType,
            AuthenticatedUser currentUser,
            Long sourceEntityId,
            String defaultName,
            SubmitMarketAssetRequest request,
            Object snapshot
    ) {
        AppUser submitter = requireCurrentUserEntity(currentUser.userId());
        MarketAsset asset = marketAssetRepository.findByAssetTypeAndSourceTenantIdAndSourceEntityId(
                assetType,
                currentUser.tenantId(),
                sourceEntityId
        ).orElseGet(MarketAsset::new);

        asset.setAssetType(assetType);
        asset.setSubmitterUser(submitter);
        asset.setSourceTenantId(currentUser.tenantId());
        asset.setSourceEntityId(sourceEntityId);
        asset.setName(normalizeName(defaultName));
        asset.setSummary(resolveSummary(request.summary(), request.description()));
        asset.setDescription(normalizeLongText(request.description()));
        asset.setSnapshotJson(writeSnapshot(snapshot));
        asset.setStatus(MarketAssetStatus.PENDING);
        asset.setReviewedBy(null);
        asset.setReviewedAt(null);
        asset.setReviewRemark(null);
        asset.setPublishedAt(null);
        if (asset.getInstallCount() == null) {
            asset.setInstallCount(0);
        }
        return toResponse(marketAssetRepository.save(asset));
    }

    private InstallationResult installAgentAsset(
            MarketAsset asset,
            AuthenticatedUser currentUser,
            Tenant tenant,
            InstallMarketAssetRequest request
    ) {
        AgentMarketSnapshot snapshot = readSnapshot(asset, AgentMarketSnapshot.class);
        Agent agent = new Agent();
        agent.setTenant(tenant);
        agent.setName(resolveUniqueName(
                normalizeInstallName(request.name(), snapshot.name()),
                candidate -> agentRepository.existsByTenant_IdAndName(tenant.getId(), candidate)
        ));
        agent.setDescription(snapshot.description());
        agent.setSystemPrompt(snapshot.systemPrompt());

        AgentChatModelBindingType bindingType = snapshot.chatModelBindingType() == null
                ? AgentChatModelBindingType.USER_MODEL
                : snapshot.chatModelBindingType();
        agent.setChatModelBindingType(bindingType);
        if (bindingType == AgentChatModelBindingType.OFFICIAL_MODEL) {
            Long officialModelConfigId = request.targetOfficialModelConfigId() != null
                    ? request.targetOfficialModelConfigId()
                    : snapshot.officialModelConfigId();
            agent.setOfficialModelConfig(requireEnabledOfficialChatModelConfig(officialModelConfigId));
            agent.setModelConfig(null);
        } else {
            agent.setModelConfig(requireEnabledTenantModelConfig(tenant.getId(), request.targetModelConfigId(), ModelType.CHAT));
            agent.setOfficialModelConfig(null);
        }

        List<MarketInstalledResourceResponse> relatedResources = new ArrayList<>();
        List<String> setupItems = new ArrayList<>();

        KnowledgeBase installedKnowledgeBase = null;
        if (snapshot.knowledgeBaseSnapshot() != null) {
            installedKnowledgeBase = installKnowledgeBaseFromSnapshot(snapshot.knowledgeBaseSnapshot(), tenant, request, relatedResources);
        } else if (request.targetKnowledgeBaseId() != null) {
            installedKnowledgeBase = requireTenantKnowledgeBase(tenant.getId(), request.targetKnowledgeBaseId());
        }
        agent.setKnowledgeBase(installedKnowledgeBase);

        boolean rerankEnabled = snapshot.rerankEnabled() && request.targetRerankModelConfigId() != null;
        agent.setRerankEnabled(rerankEnabled);
        agent.setRerankModelConfig(rerankEnabled
                ? requireEnabledTenantModelConfig(tenant.getId(), request.targetRerankModelConfigId(), ModelType.RERANK)
                : null);
        agent.setContextStrategy(snapshot.contextStrategy());
        agent.setContextWindowSize(snapshot.contextWindowSize());
        agent.setMemoryEnabled(snapshot.memoryEnabled());
        agent.setMemoryStrategy(snapshot.memoryEnabled() ? snapshot.memoryStrategy() : null);
        agent.setMemoryUpdateMessageThreshold(snapshot.memoryUpdateMessageThreshold());
        agent.setEnabled(request.enabled() == null ? snapshot.enabled() : request.enabled());

        Agent savedAgent = agentRepository.saveAndFlush(agent);
        if (snapshot.mcpServerSnapshots() != null && !snapshot.mcpServerSnapshots().isEmpty()) {
            installPackagedMcpServers(snapshot.mcpServerSnapshots(), currentUser, tenant, savedAgent, relatedResources, setupItems);
        }
        return new InstallationResult(savedAgent.getId(), savedAgent.getName(), relatedResources, setupItems);
    }

    private InstallationResult installKnowledgeBaseAsset(
            MarketAsset asset,
            Tenant tenant,
            InstallMarketAssetRequest request
    ) {
        KnowledgeBaseMarketSnapshot snapshot = readSnapshot(asset, KnowledgeBaseMarketSnapshot.class);
        List<MarketInstalledResourceResponse> relatedResources = new ArrayList<>();
        KnowledgeBase savedKnowledgeBase = installKnowledgeBaseFromSnapshot(
                new KnowledgeBaseMarketSnapshot(
                        normalizeInstallName(request.name(), snapshot.name()),
                        snapshot.description(),
                        request.enabled() == null ? snapshot.enabled() : request.enabled(),
                        snapshot.documents()
                ),
                tenant,
                request,
                relatedResources
        );
        return new InstallationResult(savedKnowledgeBase.getId(), savedKnowledgeBase.getName(), List.of(), List.of());
    }

    private InstallationResult installMcpServerAsset(
            MarketAsset asset,
            AuthenticatedUser currentUser,
            Tenant tenant,
            InstallMarketAssetRequest request
    ) {
        McpServerMarketSnapshot snapshot = readSnapshot(asset, McpServerMarketSnapshot.class);
        List<MarketInstalledResourceResponse> relatedResources = new ArrayList<>();
        List<String> setupItems = new ArrayList<>();
        McpServer savedServer = installSingleMcpServerSnapshot(
                snapshot,
                currentUser,
                tenant,
                normalizeInstallName(request.name(), snapshot.name()),
                request.targetCredentialId(),
                relatedResources,
                setupItems
        );
        return new InstallationResult(savedServer.getId(), savedServer.getName(), List.of(), setupItems);
    }

    private KnowledgeBaseMarketSnapshot buildKnowledgeBaseSnapshot(KnowledgeBase knowledgeBase, String storageScope) {
        List<KnowledgeDocumentMarketSnapshot> documents = knowledgeDocumentRepository.findAllByKnowledgeBase_IdOrderByIdDesc(knowledgeBase.getId())
                .stream()
                .map(document -> freezeKnowledgeDocument(document, storageScope))
                .toList();
        return new KnowledgeBaseMarketSnapshot(
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.isEnabled(),
                documents
        );
    }

    private KnowledgeDocumentMarketSnapshot freezeKnowledgeDocument(KnowledgeDocument document, String storageScope) {
        if (document.getStatus() != KnowledgeDocumentStatus.READY) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_NOT_READY_FOR_MARKET",
                    "Only READY knowledge documents can be submitted to market",
                    HttpStatus.BAD_REQUEST
            );
        }
        byte[] bytes = minioStorageService.downloadObject(document.getStorageBucket(), document.getStorageObjectKey());
        var storedObject = minioStorageService.uploadMarketAssetDocument(
                storageScope,
                document.getFileName(),
                document.getContentType(),
                bytes
        );
        return new KnowledgeDocumentMarketSnapshot(
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                storedObject.bucket(),
                storedObject.objectKey()
        );
    }

    private List<McpServerMarketSnapshot> buildMcpServerSnapshots(List<AgentMcpToolBinding> bindings) {
        Map<Long, List<McpTool>> toolsByServerId = new LinkedHashMap<>();
        Map<Long, McpServer> serversById = new LinkedHashMap<>();
        bindings.stream()
                .filter(AgentMcpToolBinding::isEnabled)
                .map(AgentMcpToolBinding::getTool)
                .filter(McpTool::isEnabled)
                .forEach(tool -> {
                    toolsByServerId.computeIfAbsent(tool.getServer().getId(), ignored -> new ArrayList<>()).add(tool);
                    serversById.putIfAbsent(tool.getServer().getId(), tool.getServer());
                });
        return toolsByServerId.entrySet().stream()
                .map(entry -> {
                    McpServer server = serversById.get(entry.getKey());
                    List<McpToolMarketSnapshot> toolSnapshots = entry.getValue().stream()
                            .map(tool -> new McpToolMarketSnapshot(
                                    tool.getName(),
                                    tool.getDescription(),
                                    tool.getInputSchemaJson(),
                                    tool.getRiskLevel(),
                                    tool.isEnabled()
                            ))
                            .toList();
                    return new McpServerMarketSnapshot(
                            server.getName(),
                            server.getDescription(),
                            server.getBaseUrl(),
                            server.getTransportType(),
                            server.getAuthType(),
                            server.isEnabled(),
                            toolSnapshots
                    );
                })
                .toList();
    }

    private KnowledgeBase installKnowledgeBaseFromSnapshot(
            KnowledgeBaseMarketSnapshot snapshot,
            Tenant tenant,
            InstallMarketAssetRequest request,
            List<MarketInstalledResourceResponse> relatedResources
    ) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setTenant(tenant);
        knowledgeBase.setName(resolveUniqueName(
                normalizeInstallName(null, snapshot.name()),
                candidate -> knowledgeBaseRepository.existsByTenant_IdAndName(tenant.getId(), candidate)
        ));
        knowledgeBase.setDescription(snapshot.description());
        knowledgeBase.setEmbeddingModelConfig(requireEnabledTenantModelConfig(
                tenant.getId(),
                request.targetEmbeddingModelConfigId(),
                ModelType.EMBEDDING
        ));
        knowledgeBase.setEnabled(snapshot.enabled());
        KnowledgeBase savedKnowledgeBase = knowledgeBaseRepository.saveAndFlush(knowledgeBase);
        relatedResources.add(new MarketInstalledResourceResponse(
                MarketAssetType.KNOWLEDGE_BASE.name(),
                savedKnowledgeBase.getId(),
                savedKnowledgeBase.getName()
        ));

        if (snapshot.documents() != null) {
            for (KnowledgeDocumentMarketSnapshot documentSnapshot : snapshot.documents()) {
                byte[] bytes = minioStorageService.downloadObject(documentSnapshot.storageBucket(), documentSnapshot.storageObjectKey());
                knowledgeDocumentApplicationService.uploadDocumentFromBytes(
                        savedKnowledgeBase.getId(),
                        documentSnapshot.fileName(),
                        documentSnapshot.contentType(),
                        bytes
                );
            }
        }
        return savedKnowledgeBase;
    }

    private void installPackagedMcpServers(
            List<McpServerMarketSnapshot> serverSnapshots,
            AuthenticatedUser currentUser,
            Tenant tenant,
            Agent agent,
            List<MarketInstalledResourceResponse> relatedResources,
            List<String> setupItems
    ) {
        for (McpServerMarketSnapshot serverSnapshot : serverSnapshots) {
            McpServer savedServer = installSingleMcpServerSnapshot(
                    serverSnapshot,
                    currentUser,
                    tenant,
                    normalizeInstallName(null, serverSnapshot.name()),
                    null,
                    relatedResources,
                    setupItems
            );
            List<McpTool> installedTools = mcpToolRepository.findAllByServer_IdOrderByIdAsc(savedServer.getId());
            for (McpTool tool : installedTools) {
                AgentMcpToolBinding binding = new AgentMcpToolBinding();
                binding.setTenant(tenant);
                binding.setAgent(agent);
                binding.setTool(tool);
                binding.setEnabled(tool.isEnabled());
                agentMcpToolBindingRepository.save(binding);
            }
        }
    }

    private McpServer installSingleMcpServerSnapshot(
            McpServerMarketSnapshot snapshot,
            AuthenticatedUser currentUser,
            Tenant tenant,
            String preferredName,
            Long credentialId,
            List<MarketInstalledResourceResponse> relatedResources,
            List<String> setupItems
    ) {
        McpServer server = new McpServer();
        server.setTenant(tenant);
        server.setName(resolveUniqueName(
                preferredName,
                candidate -> mcpServerRepository.existsByTenant_IdAndName(tenant.getId(), candidate)
        ));
        server.setDescription(snapshot.description());
        server.setBaseUrl(snapshot.baseUrl());
        server.setTransportType(snapshot.transportType());
        server.setAuthType(snapshot.authType());

        boolean requiresSetup = snapshot.authType() == McpAuthType.OAUTH_AUTH_CODE
                || snapshot.authType() == McpAuthType.OAUTH_CLIENT_CREDENTIALS;
        if (snapshot.authType() == McpAuthType.STATIC_BEARER) {
            if (credentialId != null) {
                server.setCredential(requireTenantCredential(currentUser.userId(), credentialId));
            } else {
                server.setCredential(null);
                requiresSetup = true;
            }
        } else {
            server.setCredential(null);
        }
        server.setEnabled(!requiresSetup && snapshot.enabled());

        McpServer savedServer = mcpServerRepository.saveAndFlush(server);
        if (snapshot.tools() != null) {
            for (McpToolMarketSnapshot toolSnapshot : snapshot.tools()) {
                McpTool tool = new McpTool();
                tool.setTenant(tenant);
                tool.setServer(savedServer);
                tool.setName(toolSnapshot.name());
                tool.setDescription(toolSnapshot.description());
                tool.setInputSchemaJson(toolSnapshot.inputSchemaJson());
                tool.setRiskLevel(toolSnapshot.riskLevel());
                tool.setEnabled(toolSnapshot.enabled());
                mcpToolRepository.save(tool);
            }
        }

        relatedResources.add(new MarketInstalledResourceResponse(
                MarketAssetType.MCP_SERVER.name(),
                savedServer.getId(),
                savedServer.getName()
        ));
        if (requiresSetup) {
            String setupMessage = switch (snapshot.authType()) {
                case STATIC_BEARER -> "MCP Server [%s] 需要绑定你自己的静态凭证后才能启用".formatted(savedServer.getName());
                case OAUTH_AUTH_CODE, OAUTH_CLIENT_CREDENTIALS ->
                        "MCP Server [%s] 需要完成 OAuth 授权后才能启用".formatted(savedServer.getName());
                case NONE -> null;
            };
            if (setupMessage != null) {
                setupItems.add(setupMessage);
            }
        }
        return savedServer;
    }

    private Credential requireTenantCredential(Long userId, Long credentialId) {
        if (credentialId == null) {
            throw new ApiException("CREDENTIAL_REQUIRED", "Installing this MCP server requires a CUSTOM credential", HttpStatus.BAD_REQUEST);
        }
        Credential credential = credentialRepository.findByIdAndUser_Id(credentialId, userId)
                .orElseThrow(() -> new ApiException("CREDENTIAL_NOT_FOUND", "Credential not found", HttpStatus.BAD_REQUEST));
        if (credential.getStatus() != CredentialStatus.ACTIVE) {
            throw new ApiException("CREDENTIAL_DISABLED", "Credential is disabled", HttpStatus.BAD_REQUEST);
        }
        if (credential.getProvider() != CredentialProvider.CUSTOM) {
            throw new ApiException("CREDENTIAL_PROVIDER_INVALID", "MCP server install requires CUSTOM credential", HttpStatus.BAD_REQUEST);
        }
        return credential;
    }

    private KnowledgeBase requireTenantKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.BAD_REQUEST));
        if (!knowledgeBase.isEnabled()) {
            throw new ApiException("KNOWLEDGE_BASE_DISABLED", "Knowledge base is disabled", HttpStatus.BAD_REQUEST);
        }
        return knowledgeBase;
    }

    private ModelConfig requireEnabledTenantModelConfig(Long tenantId, Long modelConfigId, ModelType modelType) {
        if (modelConfigId == null) {
            throw new ApiException("MODEL_CONFIG_REQUIRED", "Required model config is missing", HttpStatus.BAD_REQUEST);
        }
        ModelConfig modelConfig = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException("MODEL_CONFIG_NOT_FOUND", "Model config not found", HttpStatus.BAD_REQUEST));
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != modelType) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Model config type is invalid for install target", HttpStatus.BAD_REQUEST);
        }
        return modelConfig;
    }

    private OfficialModelConfig requireEnabledOfficialChatModelConfig(Long officialModelConfigId) {
        if (officialModelConfigId == null) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_REQUIRED", "Official model config is required", HttpStatus.BAD_REQUEST);
        }
        OfficialModelConfig officialModelConfig = officialModelConfigRepository.findById(officialModelConfigId)
                .orElseThrow(() -> new ApiException("OFFICIAL_MODEL_CONFIG_NOT_FOUND", "Official model config not found", HttpStatus.BAD_REQUEST));
        if (!officialModelConfig.isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_DISABLED", "Official model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (officialModelConfig.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Official model config must be CHAT", HttpStatus.BAD_REQUEST);
        }
        return officialModelConfig;
    }

    private MarketAsset requireApprovedAsset(Long id) {
        MarketAsset asset = requireAsset(id);
        if (asset.getStatus() != MarketAssetStatus.APPROVED) {
            throw new ApiException("MARKET_ASSET_NOT_AVAILABLE", "Market asset is not available for install", HttpStatus.BAD_REQUEST);
        }
        return asset;
    }

    private MarketAsset requireVisibleAsset(Long id) {
        MarketAsset asset = requireAsset(id);
        if (asset.getStatus() == MarketAssetStatus.APPROVED) {
            return asset;
        }
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        if (Objects.equals(asset.getSubmitterUser().getId(), currentUser.userId()) || currentUser.systemRole() == SystemRole.ADMIN) {
            return asset;
        }
        throw new ApiException("MARKET_ASSET_NOT_FOUND", "Market asset not found", HttpStatus.NOT_FOUND);
    }

    private MarketAsset requireAsset(Long id) {
        return marketAssetRepository.findById(id)
                .orElseThrow(() -> new ApiException("MARKET_ASSET_NOT_FOUND", "Market asset not found", HttpStatus.NOT_FOUND));
    }

    private AppUser requireCurrentUserEntity(Long userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));
    }

    private Tenant requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Tenant not found", HttpStatus.FORBIDDEN));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException("TENANT_DISABLED", "Current tenant is disabled", HttpStatus.FORBIDDEN);
        }
        return tenant;
    }

    private void requireAdmin() {
        requireAdminUser();
    }

    private AppUser requireAdminUser() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        if (currentUser.systemRole() != SystemRole.ADMIN) {
            throw new ApiException("FORBIDDEN", "Admin permission required", HttpStatus.FORBIDDEN);
        }
        return requireCurrentUserEntity(currentUser.userId());
    }

    private <T> T readSnapshot(MarketAsset asset, Class<T> type) {
        try {
            return objectMapper.readValue(asset.getSnapshotJson(), type);
        } catch (JsonProcessingException ex) {
            throw new ApiException("MARKET_ASSET_SNAPSHOT_INVALID", "Market asset snapshot is invalid", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String writeSnapshot(Object snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new ApiException("MARKET_ASSET_SNAPSHOT_INVALID", "Failed to serialize market asset snapshot", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private MarketAssetResponse toResponse(MarketAsset asset) {
        return new MarketAssetResponse(
                asset.getId(),
                asset.getAssetType().name(),
                asset.getSourceEntityId(),
                asset.getName(),
                asset.getSummary(),
                asset.getDescription(),
                asset.getStatus().name(),
                asset.getSubmitterUser().getId(),
                asset.getSubmitterUser().getDisplayName(),
                asset.getReviewedBy() == null ? null : asset.getReviewedBy().getId(),
                asset.getReviewedBy() == null ? null : asset.getReviewedBy().getDisplayName(),
                asset.getReviewedAt(),
                asset.getReviewRemark(),
                asset.getPublishedAt(),
                asset.getInstallCount(),
                asset.getCreatedAt(),
                asset.getUpdatedAt()
        );
    }

    private String resolveSummary(String requestSummary, String requestDescription) {
        if (StringUtils.hasText(requestSummary)) {
            return requestSummary.trim();
        }
        if (!StringUtils.hasText(requestDescription)) {
            return null;
        }
        String trimmed = requestDescription.trim();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512);
    }

    private String normalizeReviewRemark(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeName(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeLongText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeInstallName(String requestName, String defaultName) {
        String preferred = StringUtils.hasText(requestName) ? requestName.trim() : defaultName;
        return preferred == null ? "未命名副本" : preferred.trim();
    }

    private String resolveUniqueName(String preferredName, Predicate<String> existsChecker) {
        String baseName = preferredName;
        String candidate = baseName;
        int index = 2;
        while (existsChecker.test(candidate)) {
            candidate = baseName + "-" + index;
            index++;
        }
        return candidate;
    }

    private record InstallationResult(
            Long installedEntityId,
            String installedName,
            List<MarketInstalledResourceResponse> relatedResources,
            List<String> setupItems
    ) {
    }
}
