package com.enjoy.agent.agent.domain.entity;

import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Agent 实体。
 */
@Entity
@Table(
        name = "agent",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_tenant_name", columnNames = {"tenant_id", "name"})
)
public class Agent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_model_binding_type", nullable = false, length = 32)
    private AgentChatModelBindingType chatModelBindingType = AgentChatModelBindingType.USER_MODEL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_config_id")
    private ModelConfig modelConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "official_model_config_id")
    private OfficialModelConfig officialModelConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id")
    private KnowledgeBase knowledgeBase;

    @Column(name = "rerank_enabled", nullable = false)
    private boolean rerankEnabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rerank_model_config_id")
    private ModelConfig rerankModelConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "context_strategy", nullable = false, length = 32)
    private ContextStrategy contextStrategy;

    @Column(name = "context_window_size", nullable = false)
    private Integer contextWindowSize;

    @Column(name = "memory_enabled", nullable = false)
    private boolean memoryEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_strategy", length = 32)
    private MemoryStrategy memoryStrategy;

    @Column(name = "memory_update_message_threshold", nullable = false)
    private Integer memoryUpdateMessageThreshold = 6;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public AgentChatModelBindingType getChatModelBindingType() {
        return chatModelBindingType;
    }

    public void setChatModelBindingType(AgentChatModelBindingType chatModelBindingType) {
        this.chatModelBindingType = chatModelBindingType;
    }

    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    public void setModelConfig(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public OfficialModelConfig getOfficialModelConfig() {
        return officialModelConfig;
    }

    public void setOfficialModelConfig(OfficialModelConfig officialModelConfig) {
        this.officialModelConfig = officialModelConfig;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public ModelConfig getRerankModelConfig() {
        return rerankModelConfig;
    }

    public void setRerankModelConfig(ModelConfig rerankModelConfig) {
        this.rerankModelConfig = rerankModelConfig;
    }

    public ContextStrategy getContextStrategy() {
        return contextStrategy;
    }

    public void setContextStrategy(ContextStrategy contextStrategy) {
        this.contextStrategy = contextStrategy;
    }

    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public MemoryStrategy getMemoryStrategy() {
        return memoryStrategy;
    }

    public void setMemoryStrategy(MemoryStrategy memoryStrategy) {
        this.memoryStrategy = memoryStrategy;
    }

    public Integer getMemoryUpdateMessageThreshold() {
        return memoryUpdateMessageThreshold;
    }

    public void setMemoryUpdateMessageThreshold(Integer memoryUpdateMessageThreshold) {
        this.memoryUpdateMessageThreshold = memoryUpdateMessageThreshold;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
