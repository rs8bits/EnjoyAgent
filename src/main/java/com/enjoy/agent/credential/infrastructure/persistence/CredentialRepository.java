package com.enjoy.agent.credential.infrastructure.persistence;

import com.enjoy.agent.credential.domain.entity.Credential;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 凭证仓储接口。
 */
public interface CredentialRepository extends JpaRepository<Credential, Long> {

    /**
     * 查询当前用户的所有凭证。
     */
    List<Credential> findAllByUser_IdOrderByIdDesc(Long userId);

    /**
     * 按 ID 和用户 ID 查询凭证，防止越权访问。
     */
    Optional<Credential> findByIdAndUser_Id(Long id, Long userId);

    /**
     * 判断当前用户下是否已有同名凭证。
     */
    boolean existsByUser_IdAndName(Long userId, String name);

    /**
     * 更新时排除自己，判断是否与其他凭证重名。
     */
    boolean existsByUser_IdAndNameAndIdNot(Long userId, String name, Long id);
}
