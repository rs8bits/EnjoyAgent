package com.enjoy.agent.credential.infrastructure.persistence;

import com.enjoy.agent.credential.domain.entity.Credential;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 凭证仓储接口。
 */
public interface CredentialRepository extends JpaRepository<Credential, Long> {

    /**
     * 查询当前用户的所有凭证。
     */
    List<Credential> findAllByUser_IdOrderByIdDesc(Long userId);

    Page<Credential> findAllByUser_Id(Long userId, Pageable pageable);

    /**
     * 按 ID 和用户 ID 查询凭证，防止越权访问。
     */
    Optional<Credential> findByIdAndUser_Id(Long id, Long userId);

    /** 客服专用查询在 SELECT 层排除密文、历史掩码和上游 baseUrl。 */
    @Query("""
            select new com.enjoy.agent.credential.infrastructure.persistence.CredentialMetadataProjection(
                credential.id, credential.name, credential.provider,
                credential.credentialType, credential.status, credential.updatedAt
            )
            from Credential credential
            where credential.user.id = :userId
            order by credential.id desc
            """)
    Slice<CredentialMetadataProjection> findSupportMetadataByUserId(
            @Param("userId") Long userId,
            Pageable pageable
    );

    @Query("""
            select new com.enjoy.agent.credential.infrastructure.persistence.CredentialMetadataProjection(
                credential.id, credential.name, credential.provider,
                credential.credentialType, credential.status, credential.updatedAt
            )
            from Credential credential
            where credential.id = :id and credential.user.id = :userId
            """)
    Optional<CredentialMetadataProjection> findSupportMetadataByIdAndUserId(
            @Param("id") Long id,
            @Param("userId") Long userId
    );

    /**
     * 判断当前用户下是否已有同名凭证。
     */
    boolean existsByUser_IdAndName(Long userId, String name);

    /**
     * 更新时排除自己，判断是否与其他凭证重名。
     */
    boolean existsByUser_IdAndNameAndIdNot(Long userId, String name, Long id);
}
