package com.enjoy.agent.support.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.support.domain.enums.SupportActionConfirmationStatus;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次需要用户确认的客服写操作。
 *
 * <p>argumentsPayload 是在预览阶段由服务端规范化并通过归档 Hook 的 JSON。确认接口
 * 不接收新的业务参数，只按本记录执行，从而避免“用户看见 A、最终却执行 B”。</p>
 */
@Entity
@Table(
        name = "support_action_confirmation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_support_confirmation_source_call",
                        columnNames = {"source_turn_id", "skill_name", "arguments_hash"}
                ),
                @UniqueConstraint(
                        name = "uk_support_confirmation_execution_turn",
                        columnNames = "execution_turn_id"
                )
        }
)
public class SupportActionConfirmation extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SupportConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_turn_id", nullable = false)
    private SupportTurn sourceTurn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_turn_id")
    private SupportTurn executionTurn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "skill_name", nullable = false, length = 128)
    private String skillName;

    @Column(name = "skill_version", nullable = false, length = 32)
    private String skillVersion;

    @Column(name = "arguments_payload", nullable = false, columnDefinition = "TEXT")
    private String argumentsPayload;

    @Column(name = "arguments_hash", nullable = false, length = 64)
    private String argumentsHash;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "operation_summary", nullable = false, length = 1000)
    private String operationSummary;

    @Column(name = "target_updated_at")
    private Instant targetUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SupportActionConfirmationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public UUID getId() { return id; }

    public void setId(UUID id) { this.id = id; }

    public SupportConversation getConversation() { return conversation; }

    public void setConversation(SupportConversation conversation) { this.conversation = conversation; }

    public SupportTurn getSourceTurn() { return sourceTurn; }

    public void setSourceTurn(SupportTurn sourceTurn) { this.sourceTurn = sourceTurn; }

    public SupportTurn getExecutionTurn() { return executionTurn; }

    public void setExecutionTurn(SupportTurn executionTurn) { this.executionTurn = executionTurn; }

    public Tenant getTenant() { return tenant; }

    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public AppUser getUser() { return user; }

    public void setUser(AppUser user) { this.user = user; }

    public String getSkillName() { return skillName; }

    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getSkillVersion() { return skillVersion; }

    public void setSkillVersion(String skillVersion) { this.skillVersion = skillVersion; }

    public String getArgumentsPayload() { return argumentsPayload; }

    public void setArgumentsPayload(String argumentsPayload) { this.argumentsPayload = argumentsPayload; }

    public String getArgumentsHash() { return argumentsHash; }

    public void setArgumentsHash(String argumentsHash) { this.argumentsHash = argumentsHash; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getOperationSummary() { return operationSummary; }

    public void setOperationSummary(String operationSummary) { this.operationSummary = operationSummary; }

    public Instant getTargetUpdatedAt() { return targetUpdatedAt; }

    public void setTargetUpdatedAt(Instant targetUpdatedAt) { this.targetUpdatedAt = targetUpdatedAt; }

    public SupportActionConfirmationStatus getStatus() { return status; }

    public void setStatus(SupportActionConfirmationStatus status) { this.status = status; }

    public Instant getExpiresAt() { return expiresAt; }

    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConfirmedAt() { return confirmedAt; }

    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getExecutedAt() { return executedAt; }

    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }

    public Instant getCancelledAt() { return cancelledAt; }

    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
