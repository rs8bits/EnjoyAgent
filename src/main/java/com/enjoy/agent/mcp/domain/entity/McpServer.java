package com.enjoy.agent.mcp.domain.entity;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
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
import java.time.Instant;

/**
 * MCP Server 实体。
 */
@Entity
@Table(
        name = "mcp_server",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_server_tenant_name", columnNames = {"tenant_id", "name"})
)
public class McpServer extends BaseEntity {

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

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", nullable = false, length = 32)
    private McpTransportType transportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 32)
    private McpAuthType authType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_id")
    private Credential credential;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public McpTransportType getTransportType() {
        return transportType;
    }

    public void setTransportType(McpTransportType transportType) {
        this.transportType = transportType;
    }

    public McpAuthType getAuthType() {
        return authType;
    }

    public void setAuthType(McpAuthType authType) {
        this.authType = authType;
    }

    public Credential getCredential() {
        return credential;
    }

    public void setCredential(Credential credential) {
        this.credential = credential;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
