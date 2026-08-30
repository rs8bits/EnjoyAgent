package com.enjoy.agent.knowledge.domain.entity;

import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupOutboxStatus;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupType;
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
        name = "knowledge_document_cleanup_outbox",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_knowledge_document_cleanup_outbox_document",
                        columnNames = "document_id"
                ),
                @UniqueConstraint(
                        name = "uk_knowledge_document_cleanup_outbox_object",
                        columnNames = {"storage_bucket", "storage_object_key"}
                )
        }
)
public class KnowledgeDocumentCleanupOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id")
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cleanup_type", nullable = false, length = 32)
    private KnowledgeCleanupType cleanupType = KnowledgeCleanupType.DOCUMENT_DELETE;

    @Column(name = "storage_bucket", nullable = false, length = 128)
    private String storageBucket;

    @Column(name = "storage_object_key", nullable = false, length = 512)
    private String storageObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KnowledgeCleanupOutboxStatus status = KnowledgeCleanupOutboxStatus.PENDING;

    @Column(name = "cleanup_attempts", nullable = false)
    private Integer cleanupAttempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public KnowledgeCleanupType getCleanupType() { return cleanupType; }
    public void setCleanupType(KnowledgeCleanupType cleanupType) { this.cleanupType = cleanupType; }
    public String getStorageBucket() { return storageBucket; }
    public void setStorageBucket(String storageBucket) { this.storageBucket = storageBucket; }
    public String getStorageObjectKey() { return storageObjectKey; }
    public void setStorageObjectKey(String storageObjectKey) { this.storageObjectKey = storageObjectKey; }
    public KnowledgeCleanupOutboxStatus getStatus() { return status; }
    public void setStatus(KnowledgeCleanupOutboxStatus status) { this.status = status; }
    public Integer getCleanupAttempts() { return cleanupAttempts; }
    public void setCleanupAttempts(Integer cleanupAttempts) { this.cleanupAttempts = cleanupAttempts; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
