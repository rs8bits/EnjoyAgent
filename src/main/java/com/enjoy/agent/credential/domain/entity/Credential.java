package com.enjoy.agent.credential.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.domain.enums.CredentialType;
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

/**
 * 用户凭证实体。
 */
@Entity
@Table(
        name = "credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_credential_user_name", columnNames = {"user_id", "name"})
)
public class Credential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CredentialProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 32)
    private CredentialType credentialType;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(name = "secret_masked", nullable = false, length = 255)
    private String secretMasked;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(name = "description", length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CredentialStatus status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
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

    public CredentialType getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(CredentialType credentialType) {
        this.credentialType = credentialType;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public String getSecretMasked() {
        return secretMasked;
    }

    public void setSecretMasked(String secretMasked) {
        this.secretMasked = secretMasked;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public void setStatus(CredentialStatus status) {
        this.status = status;
    }
}
