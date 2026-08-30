package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocumentCleanupOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentCleanupOutboxRepository
        extends JpaRepository<KnowledgeDocumentCleanupOutbox, Long> {

    Optional<KnowledgeDocumentCleanupOutbox> findByDocumentId(Long documentId);

    Optional<KnowledgeDocumentCleanupOutbox> findByStorageBucketAndStorageObjectKey(
            String storageBucket,
            String storageObjectKey
    );

    @Query(value = """
            SELECT *
            FROM knowledge_document_cleanup_outbox
            WHERE (status = 'PENDING' AND available_at <= :now)
               OR (status = 'CLAIMED' AND claimed_at <= :stale_before)
            ORDER BY id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batch_size
            """, nativeQuery = true)
    List<KnowledgeDocumentCleanupOutbox> findDueForUpdate(
            @Param("now") Instant now,
            @Param("stale_before") Instant staleBefore,
            @Param("batch_size") int batchSize
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from KnowledgeDocumentCleanupOutbox outbox where outbox.id = :id")
    Optional<KnowledgeDocumentCleanupOutbox> findByIdForUpdate(@Param("id") Long id);
}
