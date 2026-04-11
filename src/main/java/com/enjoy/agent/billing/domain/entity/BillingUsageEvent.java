package com.enjoy.agent.billing.domain.entity;

import com.enjoy.agent.billing.domain.enums.BillingUsageEventStatus;
import com.enjoy.agent.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 官方模型调用后待异步扣费的事件快照。
 */
@Entity
@Table(
        name = "billing_usage_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_usage_event_model_call_log_id", columnNames = "model_call_log_id")
)
public class BillingUsageEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "model_call_log_id", nullable = false)
    private Long modelCallLogId;

    @Column(name = "official_model_config_id", nullable = false)
    private Long officialModelConfigId;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "input_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal inputPricePerMillion = BigDecimal.ZERO;

    @Column(name = "output_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal outputPricePerMillion = BigDecimal.ZERO;

    @Column(name = "calculated_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal calculatedAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BillingUsageEventStatus status = BillingUsageEventStatus.PENDING;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
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

    public Long getModelCallLogId() {
        return modelCallLogId;
    }

    public void setModelCallLogId(Long modelCallLogId) {
        this.modelCallLogId = modelCallLogId;
    }

    public Long getOfficialModelConfigId() {
        return officialModelConfigId;
    }

    public void setOfficialModelConfigId(Long officialModelConfigId) {
        this.officialModelConfigId = officialModelConfigId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
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

    public BigDecimal getInputPricePerMillion() {
        return inputPricePerMillion;
    }

    public void setInputPricePerMillion(BigDecimal inputPricePerMillion) {
        this.inputPricePerMillion = inputPricePerMillion;
    }

    public BigDecimal getOutputPricePerMillion() {
        return outputPricePerMillion;
    }

    public void setOutputPricePerMillion(BigDecimal outputPricePerMillion) {
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public BigDecimal getCalculatedAmount() {
        return calculatedAmount;
    }

    public void setCalculatedAmount(BigDecimal calculatedAmount) {
        this.calculatedAmount = calculatedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BillingUsageEventStatus getStatus() {
        return status;
    }

    public void setStatus(BillingUsageEventStatus status) {
        this.status = status;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
