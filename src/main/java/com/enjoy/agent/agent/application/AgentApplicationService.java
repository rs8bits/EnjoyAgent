package com.enjoy.agent.agent.application;

import com.enjoy.agent.agent.api.request.CreateAgentRequest;
import com.enjoy.agent.agent.api.request.UpdateAgentRequest;
import com.enjoy.agent.agent.api.response.AgentResponse;
import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
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
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 应用服务。
 */
@Service
public class AgentApplicationService {

    private static final int DEFAULT_MEMORY_UPDATE_MESSAGE_THRESHOLD = 6;

    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final OfficialModelConfigRepository officialModelConfigRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AgentMcpToolBindingRepository agentMcpToolBindingRepository;

    public AgentApplicationService(
            AgentRepository agentRepository,
            TenantRepository tenantRepository,
            ModelConfigRepository modelConfigRepository,
            OfficialModelConfigRepository officialModelConfigRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            AgentMcpToolBindingRepository agentMcpToolBindingRepository
    ) {
        this.agentRepository = agentRepository;
        this.tenantRepository = tenantRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.officialModelConfigRepository = officialModelConfigRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.agentMcpToolBindingRepository = agentMcpToolBindingRepository;
    }

    /**
     * 在当前租户下创建 Agent。
     */
    @Transactional
    public AgentResponse createAgent(CreateAgentRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();
        String normalizedName = normalizeName(request.name());

        if (agentRepository.existsByTenant_IdAndName(tenantId, normalizedName)) {
            throw new ApiException("AGENT_NAME_DUPLICATED", "Agent name already exists", HttpStatus.CONFLICT);
        }

        Tenant tenant = requireActiveTenant(tenantId);
        ChatModelBinding chatModelBinding = resolveChatModelBinding(
                tenantId,
                request.chatModelBindingType(),
                request.modelConfigId(),
                request.officialModelConfigId()
        );
        KnowledgeBase knowledgeBase = requireEnabledKnowledgeBase(tenantId, request.knowledgeBaseId());
        RerankBinding rerankBinding = resolveRerankBinding(
                tenantId,
                knowledgeBase,
                request.rerankEnabled(),
                request.rerankModelConfigId()
        );
        MemoryBinding memoryBinding = resolveMemoryBinding(
                request.memoryEnabled(),
                request.memoryStrategy(),
                request.memoryUpdateMessageThreshold()
        );

        Agent agent = new Agent();
        agent.setTenant(tenant);
        agent.setName(normalizedName);
        agent.setDescription(normalizeDescription(request.description()));
        agent.setSystemPrompt(normalizeSystemPrompt(request.systemPrompt()));
        agent.setChatModelBindingType(chatModelBinding.bindingType());
        agent.setModelConfig(chatModelBinding.userModelConfig());
        agent.setOfficialModelConfig(chatModelBinding.officialModelConfig());
        agent.setKnowledgeBase(knowledgeBase);
        agent.setRerankEnabled(rerankBinding.enabled());
        agent.setRerankModelConfig(rerankBinding.modelConfig());
        agent.setContextStrategy(request.contextStrategy());
        agent.setContextWindowSize(request.contextWindowSize());
        agent.setMemoryEnabled(memoryBinding.enabled());
        agent.setMemoryStrategy(memoryBinding.strategy());
        agent.setMemoryUpdateMessageThreshold(memoryBinding.updateMessageThreshold());
        agent.setEnabled(request.enabled() == null || request.enabled());

        Agent savedAgent = agentRepository.saveAndFlush(agent);
        return toResponse(savedAgent);
    }

    /**
     * 查询当前租户下的全部 Agent。
     */
    @Transactional(readOnly = true)
    public List<AgentResponse> listAgents() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return agentRepository.findAllByTenant_IdOrderByIdDesc(currentUser.tenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询当前租户下的单个 Agent。
     */
    @Transactional(readOnly = true)
    public AgentResponse getAgent(Long id) {
        return toResponse(requireTenantOwnedAgent(id));
    }

    /**
     * 更新当前租户下的 Agent。
     */
    @Transactional
    public AgentResponse updateAgent(Long id, UpdateAgentRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Agent agent = requireTenantOwnedAgent(id);
        String normalizedName = normalizeName(request.name());

        if (agentRepository.existsByTenant_IdAndNameAndIdNot(currentUser.tenantId(), normalizedName, id)) {
            throw new ApiException("AGENT_NAME_DUPLICATED", "Agent name already exists", HttpStatus.CONFLICT);
        }

        ChatModelBinding chatModelBinding = resolveChatModelBinding(
                currentUser.tenantId(),
                request.chatModelBindingType(),
                request.modelConfigId(),
                request.officialModelConfigId()
        );
        KnowledgeBase knowledgeBase = requireEnabledKnowledgeBase(currentUser.tenantId(), request.knowledgeBaseId());
        RerankBinding rerankBinding = resolveRerankBinding(
                currentUser.tenantId(),
                knowledgeBase,
                request.rerankEnabled(),
                request.rerankModelConfigId()
        );
        MemoryBinding memoryBinding = resolveMemoryBinding(
                request.memoryEnabled(),
                request.memoryStrategy(),
                request.memoryUpdateMessageThreshold()
        );

        agent.setName(normalizedName);
        agent.setDescription(normalizeDescription(request.description()));
        agent.setSystemPrompt(normalizeSystemPrompt(request.systemPrompt()));
        agent.setChatModelBindingType(chatModelBinding.bindingType());
        agent.setModelConfig(chatModelBinding.userModelConfig());
        agent.setOfficialModelConfig(chatModelBinding.officialModelConfig());
        agent.setKnowledgeBase(knowledgeBase);
        agent.setRerankEnabled(rerankBinding.enabled());
        agent.setRerankModelConfig(rerankBinding.modelConfig());
        agent.setContextStrategy(request.contextStrategy());
        agent.setContextWindowSize(request.contextWindowSize());
        agent.setMemoryEnabled(memoryBinding.enabled());
        agent.setMemoryStrategy(memoryBinding.strategy());
        agent.setMemoryUpdateMessageThreshold(memoryBinding.updateMessageThreshold());
        agent.setEnabled(request.enabled());

        Agent savedAgent = agentRepository.saveAndFlush(agent);
        return toResponse(savedAgent);
    }

    /**
     * 删除当前租户下的 Agent。
     */
    @Transactional
    public void deleteAgent(Long id) {
        agentMcpToolBindingRepository.deleteAllByAgent_Id(id);
        agentRepository.delete(requireTenantOwnedAgent(id));
    }

    /**
     * 限制当前用户只能访问自己当前租户下的 Agent。
     */
    private Agent requireTenantOwnedAgent(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return agentRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("AGENT_NOT_FOUND", "Agent not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 确保当前租户存在且处于可用状态。
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
     * 确保绑定的模型配置属于当前租户并且已经启用。
     */
    private ModelConfig requireEnabledModelConfig(Long tenantId, Long modelConfigId) {
        if (modelConfigId == null) {
            throw new ApiException("MODEL_CONFIG_REQUIRED", "User model config is required", HttpStatus.BAD_REQUEST);
        }
        ModelConfig modelConfig = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "MODEL_CONFIG_NOT_FOUND",
                        "Model config not found in current tenant",
                        HttpStatus.BAD_REQUEST
                ));
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != ModelType.CHAT) {
            throw new ApiException(
                    "MODEL_CONFIG_TYPE_INVALID",
                    "Agent can only bind CHAT model config",
                    HttpStatus.BAD_REQUEST
            );
        }
        return modelConfig;
    }

    private OfficialModelConfig requireEnabledOfficialChatModelConfig(Long officialModelConfigId) {
        if (officialModelConfigId == null) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_REQUIRED", "Official model config is required", HttpStatus.BAD_REQUEST);
        }
        OfficialModelConfig officialModelConfig = officialModelConfigRepository.findById(officialModelConfigId)
                .orElseThrow(() -> new ApiException(
                        "OFFICIAL_MODEL_CONFIG_NOT_FOUND",
                        "Official model config not found",
                        HttpStatus.BAD_REQUEST
                ));
        if (!officialModelConfig.isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_DISABLED", "Official model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (officialModelConfig.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Agent can only bind CHAT official model config", HttpStatus.BAD_REQUEST);
        }
        if (!officialModelConfig.getOfficialCredential().isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CREDENTIAL_DISABLED", "Official model credential is disabled", HttpStatus.BAD_REQUEST);
        }
        return officialModelConfig;
    }

    private ChatModelBinding resolveChatModelBinding(
            Long tenantId,
            AgentChatModelBindingType bindingType,
            Long modelConfigId,
            Long officialModelConfigId
    ) {
        AgentChatModelBindingType resolvedBindingType = bindingType == null ? AgentChatModelBindingType.USER_MODEL : bindingType;
        if (resolvedBindingType == AgentChatModelBindingType.OFFICIAL_MODEL) {
            if (modelConfigId != null) {
                throw new ApiException(
                        "AGENT_OFFICIAL_MODEL_MUST_NOT_BIND_USER_MODEL",
                        "Official model binding must not provide user model config id",
                        HttpStatus.BAD_REQUEST
                );
            }
            return new ChatModelBinding(
                    AgentChatModelBindingType.OFFICIAL_MODEL,
                    null,
                    requireEnabledOfficialChatModelConfig(officialModelConfigId)
            );
        }
        if (officialModelConfigId != null) {
            throw new ApiException(
                    "AGENT_USER_MODEL_MUST_NOT_BIND_OFFICIAL_MODEL",
                    "User model binding must not provide official model config id",
                    HttpStatus.BAD_REQUEST
            );
        }
        return new ChatModelBinding(
                AgentChatModelBindingType.USER_MODEL,
                requireEnabledModelConfig(tenantId, modelConfigId),
                null
        );
    }

    /**
     * 确保绑定的知识库属于当前租户并且已经启用。
     */
    private KnowledgeBase requireEnabledKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return null;
        }

        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_BASE_NOT_FOUND",
                        "Knowledge base not found in current tenant",
                        HttpStatus.BAD_REQUEST
                ));
        if (!knowledgeBase.isEnabled()) {
            throw new ApiException("KNOWLEDGE_BASE_DISABLED", "Knowledge base is disabled", HttpStatus.BAD_REQUEST);
        }
        return knowledgeBase;
    }

    /**
     * 校验 Agent 的 rerank 绑定。
     */
    private RerankBinding resolveRerankBinding(
            Long tenantId,
            KnowledgeBase knowledgeBase,
            Boolean rerankEnabled,
            Long rerankModelConfigId
    ) {
        if (!Boolean.TRUE.equals(rerankEnabled)) {
            return new RerankBinding(false, null);
        }
        if (knowledgeBase == null) {
            throw new ApiException(
                    "AGENT_RERANK_REQUIRES_KNOWLEDGE_BASE",
                    "Agent must bind a knowledge base before enabling rerank",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (rerankModelConfigId == null) {
            throw new ApiException(
                    "RERANK_MODEL_CONFIG_REQUIRED",
                    "Rerank model config is required when rerank is enabled",
                    HttpStatus.BAD_REQUEST
            );
        }
        return new RerankBinding(true, requireEnabledRerankModelConfig(tenantId, rerankModelConfigId));
    }

    /**
     * 确保绑定的重排模型配置属于当前租户并且已经启用。
     */
    private ModelConfig requireEnabledRerankModelConfig(Long tenantId, Long modelConfigId) {
        ModelConfig modelConfig = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "MODEL_CONFIG_NOT_FOUND",
                        "Model config not found in current tenant",
                        HttpStatus.BAD_REQUEST
                ));
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != ModelType.RERANK) {
            throw new ApiException(
                    "MODEL_CONFIG_TYPE_INVALID",
                    "Agent rerank must bind a RERANK model config",
                    HttpStatus.BAD_REQUEST
            );
        }
        return modelConfig;
    }

    /**
     * 解析 Agent 的会话记忆配置。
     */
    private MemoryBinding resolveMemoryBinding(
            Boolean memoryEnabled,
            MemoryStrategy memoryStrategy,
            Integer memoryUpdateMessageThreshold
    ) {
        if (!Boolean.TRUE.equals(memoryEnabled)) {
            return new MemoryBinding(false, null, DEFAULT_MEMORY_UPDATE_MESSAGE_THRESHOLD);
        }
        int threshold = memoryUpdateMessageThreshold == null
                ? DEFAULT_MEMORY_UPDATE_MESSAGE_THRESHOLD
                : memoryUpdateMessageThreshold;
        return new MemoryBinding(true, memoryStrategy == null ? MemoryStrategy.SESSION_SUMMARY : memoryStrategy, threshold);
    }

    /**
     * 转成接口返回对象。
     */
    private AgentResponse toResponse(Agent agent) {
        ModelConfig modelConfig = agent.getModelConfig();
        OfficialModelConfig officialModelConfig = agent.getOfficialModelConfig();
        KnowledgeBase knowledgeBase = agent.getKnowledgeBase();
        ModelConfig rerankModelConfig = agent.getRerankModelConfig();
        return new AgentResponse(
                agent.getId(),
                agent.getTenant().getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                agent.getChatModelBindingType().name(),
                modelConfig == null ? null : modelConfig.getId(),
                modelConfig == null ? null : modelConfig.getName(),
                officialModelConfig == null ? null : officialModelConfig.getId(),
                officialModelConfig == null ? null : officialModelConfig.getName(),
                knowledgeBase == null ? null : knowledgeBase.getId(),
                knowledgeBase == null ? null : knowledgeBase.getName(),
                agent.isRerankEnabled(),
                rerankModelConfig == null ? null : rerankModelConfig.getId(),
                rerankModelConfig == null ? null : rerankModelConfig.getName(),
                agent.getContextStrategy().name(),
                agent.getContextWindowSize(),
                agent.isMemoryEnabled(),
                agent.getMemoryStrategy() == null ? null : agent.getMemoryStrategy().name(),
                agent.getMemoryUpdateMessageThreshold(),
                agent.isEnabled(),
                agent.getCreatedAt(),
                agent.getUpdatedAt()
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

    /**
     * 统一处理 system prompt 空白。
     */
    private String normalizeSystemPrompt(String systemPrompt) {
        return systemPrompt == null ? null : systemPrompt.trim();
    }

    private record RerankBinding(boolean enabled, ModelConfig modelConfig) {
    }

    private record MemoryBinding(boolean enabled, MemoryStrategy strategy, Integer updateMessageThreshold) {
    }

    private record ChatModelBinding(
            AgentChatModelBindingType bindingType,
            ModelConfig userModelConfig,
            OfficialModelConfig officialModelConfig
    ) {
    }
}
