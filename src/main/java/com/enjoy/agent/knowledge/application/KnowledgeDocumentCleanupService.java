package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocumentCleanupOutbox;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupOutboxStatus;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupType;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentCleanupOutboxRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDocumentCleanupService {

    private static final int ERROR_MAX_LENGTH = 1000;
    private final KnowledgeDocumentCleanupOutboxRepository outboxRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeIngestProperties properties;
    private final Clock clock;

    public KnowledgeDocumentCleanupService(
            KnowledgeDocumentCleanupOutboxRepository outboxRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeIngestProperties properties,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public KnowledgeDocumentCleanupOutbox enqueue(
            Long documentId,
            String storageBucket,
            String storageObjectKey
    ) {
        return outboxRepository.findByDocumentId(documentId).orElseGet(() -> {
            KnowledgeDocumentCleanupOutbox outbox = new KnowledgeDocumentCleanupOutbox();
            outbox.setDocumentId(documentId);
            outbox.setCleanupType(KnowledgeCleanupType.DOCUMENT_DELETE);
            outbox.setStorageBucket(storageBucket);
            outbox.setStorageObjectKey(storageObjectKey);
            outbox.setStatus(KnowledgeCleanupOutboxStatus.PENDING);
            outbox.setCleanupAttempts(0);
            // Let a fenced worker finish its currently-running bounded Elasticsearch request
            // before the final idempotent delete, preventing a late bulk response from recreating
            // orphaned search documents after cleanup.
            outbox.setAvailableAt(Instant.now(clock).plus(properties.getCleanupGracePeriod()));
            return outboxRepository.save(outbox);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeDocumentCleanupOutbox enqueueOrphan(
            String storageBucket,
            String storageObjectKey
    ) {
        return outboxRepository.findByStorageBucketAndStorageObjectKey(storageBucket, storageObjectKey)
                .orElseGet(() -> {
                    KnowledgeDocumentCleanupOutbox outbox = new KnowledgeDocumentCleanupOutbox();
                    outbox.setCleanupType(KnowledgeCleanupType.ORPHAN_OBJECT);
                    outbox.setStorageBucket(storageBucket);
                    outbox.setStorageObjectKey(storageObjectKey);
                    outbox.setStatus(KnowledgeCleanupOutboxStatus.PENDING);
                    outbox.setCleanupAttempts(0);
                    outbox.setAvailableAt(Instant.now(clock));
                    return outboxRepository.save(outbox);
                });
    }

    @Transactional
    public List<KnowledgeDocumentCleanupOutbox> claimDue() {
        Instant now = Instant.now(clock);
        List<KnowledgeDocumentCleanupOutbox> claimed = outboxRepository.findDueForUpdate(
                now,
                now.minus(properties.getCleanupClaimTimeout()),
                properties.getCleanupBatchSize()
        );
        claimed.forEach(outbox -> {
            outbox.setStatus(KnowledgeCleanupOutboxStatus.CLAIMED);
            outbox.setClaimedAt(now);
            outbox.setCleanupAttempts(Objects.requireNonNullElse(outbox.getCleanupAttempts(), 0) + 1);
            outbox.setLastError(null);
        });
        return List.copyOf(claimed);
    }

    @Transactional
    public void markCompleted(Long outboxId) {
        outboxRepository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != KnowledgeCleanupOutboxStatus.CLAIMED) {
                return;
            }
            if (outbox.getCleanupType() == KnowledgeCleanupType.DOCUMENT_DELETE
                    && outbox.getDocumentId() != null) {
                documentRepository.findByIdForUpdate(outbox.getDocumentId()).ifPresent(document -> {
                    if (document.getStatus() == KnowledgeDocumentStatus.DELETING) {
                        documentRepository.delete(document);
                    }
                });
            }
            outbox.setStatus(KnowledgeCleanupOutboxStatus.COMPLETED);
            outbox.setClaimedAt(null);
            outbox.setCompletedAt(Instant.now(clock));
            outbox.setLastError(null);
        });
    }

    @Transactional
    public void markFailed(Long outboxId, RuntimeException failure) {
        outboxRepository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != KnowledgeCleanupOutboxStatus.CLAIMED) {
                return;
            }
            outbox.setStatus(KnowledgeCleanupOutboxStatus.PENDING);
            outbox.setClaimedAt(null);
            outbox.setAvailableAt(Instant.now(clock).plus(properties.getCleanupRetryDelay()));
            outbox.setLastError(truncate(failure.getMessage()));
        });
    }

    private String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }
}
