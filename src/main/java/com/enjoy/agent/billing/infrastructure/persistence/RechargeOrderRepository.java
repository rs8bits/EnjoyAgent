package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    List<RechargeOrder> findAllByUser_IdOrderByIdDesc(Long userId);

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    Page<RechargeOrder> findAllByUser_Id(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    Optional<RechargeOrder> findByIdAndUser_Id(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    @Query("select rechargeOrder from RechargeOrder rechargeOrder where rechargeOrder.id = :id")
    Optional<RechargeOrder> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    List<RechargeOrder> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    Page<RechargeOrder> findAllPagedBy(Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    List<RechargeOrder> findAllByStatusOrderByIdDesc(RechargeOrderStatus status);

    @EntityGraph(attributePaths = {"user", "reviewedBy"})
    Page<RechargeOrder> findAllByStatus(RechargeOrderStatus status, Pageable pageable);
}
