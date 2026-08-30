package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.BillingCreditReservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BillingCreditReservationRepository extends JpaRepository<BillingCreditReservation, Long> {

    boolean existsByOfficialModelConfigId(Long officialModelConfigId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from BillingCreditReservation reservation where reservation.id = :id")
    Optional<BillingCreditReservation> findByIdForUpdate(@Param("id") Long id);

}
