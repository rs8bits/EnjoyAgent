package com.enjoy.agent.model.domain.entity;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
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
import java.math.BigDecimal;

/**
 * 租户级模型配置实体。
 */
@Entity
@Table(
        name = "model_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_model_config_tenant_name", columnNames = {"tenant_id", "name"})
)
public class ModelConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_source", nullable = false, length = 32)
    private ModelCredentialSource credentialSource = ModelCredentialSource.USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_id")
    private Credential credential;

    @Column(name = "temperature", precision = 4, scale = 3)
    private BigDecimal temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

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

    public ModelCredentialSource getCredentialSource() {
        return credentialSource;
    }

    public void setCredentialSource(ModelCredentialSource credentialSource) {
        this.credentialSource = credentialSource;
    }

    public Credential getCredential() {
        return credential;
    }

    public void setCredential(Credential credential) {
        this.credential = credential;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
