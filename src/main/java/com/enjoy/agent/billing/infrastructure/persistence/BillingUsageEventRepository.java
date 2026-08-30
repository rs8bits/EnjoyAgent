package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.BillingUsageEvent;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BillingUsageEventRepository extends JpaRepository<BillingUsageEvent, Long> {

    Optional<BillingUsageEvent> findByModelCallLogId(Long modelCallLogId);

    boolean existsByOfficialModelConfigId(Long officialModelConfigId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from BillingUsageEvent event where event.id = :id")
    Optional<BillingUsageEvent> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT event.id
            FROM billing_usage_event event
            LEFT JOIN billing_usage_outbox outbox ON outbox.usage_event_id = event.id
            WHERE event.status = 'PENDING' AND outbox.id IS NULL
            ORDER BY event.id ASC
            FOR UPDATE OF event SKIP LOCKED
            LIMIT :batch_size
            """, nativeQuery = true)
    List<Long> findPendingIdsMissingOutboxForUpdate(@Param("batch_size") int batchSize);

}
