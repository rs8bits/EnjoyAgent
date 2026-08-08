package com.enjoy.agent.knowledge.domain.entity;

import com.enjoy.agent.knowledge.domain.enums.KnowledgeIngestOutboxStatus;
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

/**
 * 数据库与 RabbitMQ 之间的知识文档导入 Outbox。
 */
@Entity
@Table(
        name = "knowledge_ingest_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_ingest_outbox_delivery",
                columnNames = {"document_id", "delivery_attempt"}
        )
)
public class KnowledgeIngestOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "delivery_attempt", nullable = false)
    private Integer deliveryAttempt;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KnowledgeIngestOutboxStatus status = KnowledgeIngestOutboxStatus.PENDING;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getDeliveryAttempt() {
        return deliveryAttempt;
    }

    public void setDeliveryAttempt(Integer deliveryAttempt) {
        this.deliveryAttempt = deliveryAttempt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public KnowledgeIngestOutboxStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeIngestOutboxStatus status) {
        this.status = status;
    }

    public Integer getPublishAttempts() {
        return publishAttempts;
    }

    public void setPublishAttempts(Integer publishAttempts) {
        this.publishAttempts = publishAttempts;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = availableAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
