package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.BillingUsageEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BillingUsageEventRepository extends JpaRepository<BillingUsageEvent, Long> {

    Optional<BillingUsageEvent> findByModelCallLogId(Long modelCallLogId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from BillingUsageEvent event where event.id = :id")
    Optional<BillingUsageEvent> findByIdForUpdate(@Param("id") Long id);

    void deleteAllBySessionId(Long sessionId);
}
