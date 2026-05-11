package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {

    List<RechargeOrder> findAllByUser_IdOrderByIdDesc(Long userId);

    Page<RechargeOrder> findAllByUser_Id(Long userId, Pageable pageable);

    Optional<RechargeOrder> findByIdAndUser_Id(Long id, Long userId);

    List<RechargeOrder> findAllByOrderByIdDesc();

    Page<RechargeOrder> findAllPagedBy(Pageable pageable);

    List<RechargeOrder> findAllByStatusOrderByIdDesc(RechargeOrderStatus status);

    Page<RechargeOrder> findAllByStatus(RechargeOrderStatus status, Pageable pageable);
}
