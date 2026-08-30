package com.enjoy.agent.billing.domain.entity;

import com.enjoy.agent.billing.domain.enums.BillingOutboxStatus;
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
import java.time.Instant;

@Entity
@Table(
        name = "billing_usage_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_usage_outbox_event", columnNames = "usage_event_id")
)
public class BillingUsageOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_event_id", nullable = false)
    private Long usageEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BillingOutboxStatus status = BillingOutboxStatus.PENDING;

    @Column(name = "publish_attempts", nullable = false)
    private Integer publishAttempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUsageEventId() { return usageEventId; }
    public void setUsageEventId(Long usageEventId) { this.usageEventId = usageEventId; }
    public BillingOutboxStatus getStatus() { return status; }
    public void setStatus(BillingOutboxStatus status) { this.status = status; }
    public Integer getPublishAttempts() { return publishAttempts; }
    public void setPublishAttempts(Integer publishAttempts) { this.publishAttempts = publishAttempts; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
