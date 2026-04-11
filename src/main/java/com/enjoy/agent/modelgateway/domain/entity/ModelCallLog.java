package com.enjoy.agent.modelgateway.domain.entity;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.modelgateway.domain.enums.ModelCallStatus;
import com.enjoy.agent.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 模型调用日志实体。
 */
@Entity
@Table(name = "model_call_log")
public class ModelCallLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_message_id", nullable = false)
    private Long userMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CredentialProvider provider;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_source", nullable = false, length = 32)
    private CredentialSource credentialSource;

    @Column(name = "credential_id")
    private Long credentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ModelCallStatus status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(Long userMessageId) {
        this.userMessageId = userMessageId;
    }

    public CredentialProvider getProvider() {
        return provider;
    }

    public void setProvider(CredentialProvider provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public CredentialSource getCredentialSource() {
        return credentialSource;
    }

    public void setCredentialSource(CredentialSource credentialSource) {
        this.credentialSource = credentialSource;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

    public ModelCallStatus getStatus() {
        return status;
    }

    public void setStatus(ModelCallStatus status) {
        this.status = status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
