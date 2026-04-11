package com.enjoy.agent.auth.infrastructure.persistence;

import com.enjoy.agent.auth.domain.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户仓储接口。
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * 按邮箱查找用户。
     */
    Optional<AppUser> findByEmail(String email);

    /**
     * 判断邮箱是否已存在。
     */
    boolean existsByEmail(String email);
}
