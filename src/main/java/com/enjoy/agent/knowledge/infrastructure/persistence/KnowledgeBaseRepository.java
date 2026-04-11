package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库仓储接口。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /**
     * 查询当前租户下的知识库列表，并预加载 embedding 模型配置。
     */
    @EntityGraph(attributePaths = {"tenant", "embeddingModelConfig", "embeddingModelConfig.credential"})
    List<KnowledgeBase> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    /**
     * 按 ID 和租户查询知识库。
     */
    @EntityGraph(attributePaths = {"tenant", "embeddingModelConfig", "embeddingModelConfig.credential"})
    Optional<KnowledgeBase> findByIdAndTenant_Id(Long id, Long tenantId);

    /**
     * 判断当前租户下是否已有同名知识库。
     */
    boolean existsByTenant_IdAndName(Long tenantId, String name);

    /**
     * 更新时排除自己判断是否重名。
     */
    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);
}
