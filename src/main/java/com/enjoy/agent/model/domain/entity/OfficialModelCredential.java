package com.enjoy.agent.model.domain.entity;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
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

/**
 * 平台官方模型使用的凭证。
 */
@Entity
@Table(
        name = "official_model_credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_official_model_credential_name", columnNames = "name")
)
public class OfficialModelCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CredentialProvider provider;

    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    @Column(name = "secret_ciphertext", nullable = false, length = 2048)
    private String secretCiphertext;

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
