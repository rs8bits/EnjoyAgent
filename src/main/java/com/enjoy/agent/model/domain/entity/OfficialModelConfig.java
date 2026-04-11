package com.enjoy.agent.model.domain.entity;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.shared.domain.BaseEntity;
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
import java.math.BigDecimal;

/**
 * 平台官方模型配置。
 */
@Entity
@Table(
        name = "official_model_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_official_model_config_name", columnNames = "name")
)
public class OfficialModelConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CredentialProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false, length = 32)
    private ModelType modelType;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "official_credential_id", nullable = false)
    private OfficialModelCredential officialCredential;

    @Column(name = "temperature", precision = 4, scale = 3)
    private BigDecimal temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "input_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal inputPricePerMillion = BigDecimal.ZERO;

    @Column(name = "output_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal outputPricePerMillion = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CredentialProvider getProvider() {
        return provider;
    }

    public void setProvider(CredentialProvider provider) {
        this.provider = provider;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public void setModelType(ModelType modelType) {
        this.modelType = modelType;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public OfficialModelCredential getOfficialCredential() {
        return officialCredential;
    }

    public void setOfficialCredential(OfficialModelCredential officialCredential) {
        this.officialCredential = officialCredential;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
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

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
