package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.UserWalletTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWalletTransactionRepository extends JpaRepository<UserWalletTransaction, Long> {

    List<UserWalletTransaction> findTop100ByUser_IdOrderByIdDesc(Long userId);
}
