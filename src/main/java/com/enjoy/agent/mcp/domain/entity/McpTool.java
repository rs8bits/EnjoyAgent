package com.enjoy.agent.mcp.domain.entity;

import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
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
 * MCP Tool 实体。
 */
@Entity
@Table(
        name = "mcp_tool",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_tool_server_name", columnNames = {"server_id", "name"})
)
public class McpTool extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServer server;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 32)
    private McpToolRiskLevel riskLevel;

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

    public McpServer getServer() {
        return server;
    }

    public void setServer(McpServer server) {
        this.server = server;
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

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public McpToolRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(McpToolRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
