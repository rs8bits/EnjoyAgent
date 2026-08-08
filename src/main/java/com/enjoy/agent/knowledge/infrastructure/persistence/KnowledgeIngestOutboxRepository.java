package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * 知识文档导入 Outbox 仓储。
 */
public interface KnowledgeIngestOutboxRepository extends JpaRepository<KnowledgeIngestOutbox, Long> {

    @Query(value = """
            SELECT *
            FROM knowledge_ingest_outbox
            WHERE (status = 'PENDING' AND available_at <= :now)
               OR (status = 'CLAIMED' AND claimed_at <= :stale_before)
            ORDER BY id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batch_size
            """, nativeQuery = true)
    List<KnowledgeIngestOutbox> findDueForUpdate(
            @Param("now") Instant now,
            @Param("stale_before") Instant staleBefore,
            @Param("batch_size") int batchSize
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from KnowledgeIngestOutbox outbox where outbox.id = :id")
    Optional<KnowledgeIngestOutbox> findByIdForUpdate(@Param("id") Long id);
}
