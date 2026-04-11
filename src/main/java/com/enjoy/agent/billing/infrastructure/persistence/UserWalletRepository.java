package com.enjoy.agent.billing.infrastructure.persistence;

import com.enjoy.agent.billing.domain.entity.UserWallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {

    Optional<UserWallet> findByUser_Id(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from UserWallet wallet where wallet.user.id = :userId")
    Optional<UserWallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
