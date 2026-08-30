package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BillingUsageOutboxRepository extends JpaRepository<BillingUsageOutbox, Long> {

    Optional<BillingUsageOutbox> findByUsageEventId(Long usageEventId);

    @Query(value = """
            SELECT *
            FROM billing_usage_outbox
            WHERE (status = 'PENDING' AND available_at <= :now)
               OR (status = 'CLAIMED' AND claimed_at <= :stale_before)
            ORDER BY id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batch_size
            """, nativeQuery = true)
    List<BillingUsageOutbox> findDueForUpdate(
            @Param("now") Instant now,
            @Param("stale_before") Instant staleBefore,
            @Param("batch_size") int batchSize
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from BillingUsageOutbox outbox where outbox.id = :id")
    Optional<BillingUsageOutbox> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from BillingUsageOutbox outbox where outbox.usageEventId = :eventId")
    Optional<BillingUsageOutbox> findByUsageEventIdForUpdate(@Param("eventId") Long eventId);

    @Modifying
    @Query(value = """
            UPDATE billing_usage_outbox outbox
            SET status = 'PENDING', available_at = :now, claimed_at = NULL,
                last_error = 'reconciled pending billing event', updated_at = :now
            FROM billing_usage_event event
            WHERE event.id = outbox.usage_event_id
              AND event.status = 'PENDING'
              AND outbox.status = 'PUBLISHED'
              AND outbox.published_at <= :stale_before
            """, nativeQuery = true)
    int resetPublishedForUnprocessedEvents(
            @Param("now") Instant now,
            @Param("stale_before") Instant staleBefore
    );
}
