package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.knowledge.api.request.CreateKnowledgeBaseRequest;
import com.enjoy.agent.knowledge.api.request.UpdateKnowledgeBaseRequest;
import com.enjoy.agent.knowledge.api.response.KnowledgeBaseResponse;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库应用服务。
 */
@Service
public class KnowledgeBaseApplicationService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final AgentRepository agentRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final TenantRepository tenantRepository;

    public KnowledgeBaseApplicationService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            AgentRepository agentRepository,
            ModelConfigRepository modelConfigRepository,
            TenantRepository tenantRepository
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.agentRepository = agentRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * 创建知识库。
     */
    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();
        String normalizedName = normalizeName(request.name());

        if (knowledgeBaseRepository.existsByTenant_IdAndName(tenantId, normalizedName)) {
            throw new ApiException("KNOWLEDGE_BASE_NAME_DUPLICATED", "Knowledge base name already exists", HttpStatus.CONFLICT);
        }

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setTenant(requireActiveTenant(tenantId));
        knowledgeBase.setName(normalizedName);
        knowledgeBase.setDescription(normalizeDescription(request.description()));
        knowledgeBase.setEmbeddingModelConfig(requireRunnableEmbeddingModelConfig(tenantId, request.embeddingModelConfigId()));
        knowledgeBase.setEnabled(request.enabled() == null || request.enabled());

        return toResponse(knowledgeBaseRepository.saveAndFlush(knowledgeBase));
    }

    /**
     * 查询当前租户下的知识库列表。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return knowledgeBaseRepository.findAllByTenant_IdOrderByIdDesc(currentUser.tenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询知识库详情。
     */
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getKnowledgeBase(Long id) {
        return toResponse(requireTenantOwnedKnowledgeBase(id));
    }

    /**
     * 更新知识库。
     */
    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBase(Long id, UpdateKnowledgeBaseRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        KnowledgeBase knowledgeBase = requireTenantOwnedKnowledgeBase(id);
        String normalizedName = normalizeName(request.name());

        if (knowledgeBaseRepository.existsByTenant_IdAndNameAndIdNot(currentUser.tenantId(), normalizedName, id)) {
            throw new ApiException("KNOWLEDGE_BASE_NAME_DUPLICATED", "Knowledge base name already exists", HttpStatus.CONFLICT);
        }

        knowledgeBase.setName(normalizedName);
        knowledgeBase.setDescription(normalizeDescription(request.description()));
        knowledgeBase.setEmbeddingModelConfig(requireRunnableEmbeddingModelConfig(currentUser.tenantId(), request.embeddingModelConfigId()));
        knowledgeBase.setEnabled(request.enabled());

        return toResponse(knowledgeBaseRepository.saveAndFlush(knowledgeBase));
    }

    /**
     * 删除知识库。
     */
    @Transactional
    public void deleteKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = requireTenantOwnedKnowledgeBase(id);
        if (knowledgeDocumentRepository.existsByKnowledgeBase_Id(id)) {
            throw new ApiException("KNOWLEDGE_BASE_NOT_EMPTY", "Knowledge base still contains documents", HttpStatus.CONFLICT);
        }
        if (agentRepository.existsByKnowledgeBase_Id(id)) {
            throw new ApiException("KNOWLEDGE_BASE_IN_USE", "Knowledge base is still bound by some agents", HttpStatus.CONFLICT);
        }
        knowledgeBaseRepository.delete(knowledgeBase);
    }

    /**
     * 为其他服务提供当前租户下的知识库实体校验。
     */
    @Transactional(readOnly = true)
    public KnowledgeBase requireRunnableKnowledgeBase(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireTenantOwnedKnowledgeBase(knowledgeBaseId);
        if (!knowledgeBase.isEnabled()) {
            throw new ApiException("KNOWLEDGE_BASE_DISABLED", "Knowledge base is disabled", HttpStatus.BAD_REQUEST);
        }
        requireRunnableEmbeddingModelConfig(knowledgeBase.getTenant().getId(), knowledgeBase.getEmbeddingModelConfig().getId());
        return knowledgeBase;
    }

    /**
     * 将知识库实体转换成聊天运行时快照。
     */
    @Transactional(readOnly = true)
    public PreparedKnowledgeBase snapshotKnowledgeBase(KnowledgeBase knowledgeBase) {
        return snapshotKnowledgeBase(knowledgeBase, false, null);
    }

    /**
     * 将知识库实体转换成聊天运行时快照，并带上 Agent 级 rerank 配置。
     */
    @Transactional(readOnly = true)
    public PreparedKnowledgeBase snapshotKnowledgeBase(
            KnowledgeBase knowledgeBase,
            boolean rerankEnabled,
            ModelConfig rerankModelConfig
    ) {
        ModelConfig embeddingModelConfig = knowledgeBase.getEmbeddingModelConfig();
        return new PreparedKnowledgeBase(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                snapshotModelConfig(embeddingModelConfig),
                rerankEnabled,
                rerankEnabled && rerankModelConfig != null ? snapshotModelConfig(rerankModelConfig) : null
        );
    }

    /**
     * 查询当前租户自己的知识库。
     */
    private KnowledgeBase requireTenantOwnedKnowledgeBase(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return knowledgeBaseRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 校验 embedding 模型配置是否可用。
     */
    private ModelConfig requireRunnableEmbeddingModelConfig(Long tenantId, Long modelConfigId) {
        ModelConfig modelConfig = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException("MODEL_CONFIG_NOT_FOUND", "Model config not found", HttpStatus.BAD_REQUEST));
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != ModelType.EMBEDDING) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Knowledge base must bind an EMBEDDING model config", HttpStatus.BAD_REQUEST);
        }
        if (!modelConfig.getProvider().isOpenAiCompatibleRuntime()) {
            throw new ApiException("MODEL_PROVIDER_NOT_SUPPORTED", "Current runtime only supports OpenAI-compatible embedding providers", HttpStatus.BAD_REQUEST);
        }
        return modelConfig;
    }

    /**
     * 确保当前租户处于可用状态。
     */
    private Tenant requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Current tenant not found", HttpStatus.FORBIDDEN));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException("TENANT_DISABLED", "Current tenant is disabled", HttpStatus.FORBIDDEN);
        }
        return tenant;
    }

    /**
     * 转换成接口返回对象。
     */
    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getTenant().getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getEmbeddingModelConfig().getId(),
                knowledgeBase.getEmbeddingModelConfig().getName(),
                knowledgeBase.isEnabled(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }

    /**
     * 统一处理名称空白。
     */
    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    /**
     * 统一处理可选描述。
     */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private com.enjoy.agent.modelgateway.application.PreparedModelConfig snapshotModelConfig(ModelConfig modelConfig) {
        return new com.enjoy.agent.modelgateway.application.PreparedModelConfig(
                modelConfig.getProvider(),
                modelConfig.getModelType(),
                modelConfig.getModelName(),
                com.enjoy.agent.modelgateway.domain.enums.CredentialSource.valueOf(resolveModelCredentialSource(modelConfig).name()),
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getId(),
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getSecretCiphertext(),
                modelConfig.getTemperature(),
                modelConfig.getMaxTokens(),
                null,
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getBaseUrl(),
                null,
                null,
                null
        );
    }

    private ModelCredentialSource resolveModelCredentialSource(ModelConfig modelConfig) {
        if (modelConfig.getCredentialSource() != null) {
            return modelConfig.getCredentialSource();
        }
        return modelConfig.getCredential() == null ? ModelCredentialSource.PLATFORM : ModelCredentialSource.USER;
    }
}
