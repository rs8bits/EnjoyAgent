package com.enjoy.agent.model.infrastructure.persistence;

import com.enjoy.agent.model.domain.entity.ModelConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 模型配置仓储接口。
 */
public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    /**
     * 查询当前租户的全部模型配置，并预加载租户和凭证信息。
     */
    @EntityGraph(attributePaths = {"tenant", "credential"})
    List<ModelConfig> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    /**
     * 按 ID 和租户查询模型配置，防止跨租户访问。
     */
    @EntityGraph(attributePaths = {"tenant", "credential"})
    Optional<ModelConfig> findByIdAndTenant_Id(Long id, Long tenantId);

    /**
     * 判断当前租户下是否已有同名模型配置。
     */
    boolean existsByTenant_IdAndName(Long tenantId, String name);

    /**
     * 更新时排除自己，判断是否和其他模型配置重名。
     */
    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);

    /**
     * 判断是否已有模型配置引用某个凭证。
     */
    boolean existsByCredential_Id(Long credentialId);
}
