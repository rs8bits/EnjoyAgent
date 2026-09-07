package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 知识库仓储接口。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /**
     * 查询当前租户下的知识库列表，并预加载 embedding 模型配置。
     */
    @EntityGraph(attributePaths = {"tenant", "embeddingModelConfig", "embeddingModelConfig.credential"})
    List<KnowledgeBase> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    @EntityGraph(attributePaths = {"tenant", "embeddingModelConfig", "embeddingModelConfig.credential"})
    Page<KnowledgeBase> findAllByTenant_Id(Long tenantId, Pageable pageable);

    /**
     * 按 ID 和租户查询知识库。
     */
    @EntityGraph(attributePaths = {"tenant", "embeddingModelConfig", "embeddingModelConfig.credential"})
    Optional<KnowledgeBase> findByIdAndTenant_Id(Long id, Long tenantId);

    /** 客服专用列表投影，不连接 embedding 模型和 Credential。 */
    @Query("""
            select new com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseSummaryProjection(
                knowledgeBase.id, knowledgeBase.name, knowledgeBase.description,
                knowledgeBase.enabled, knowledgeBase.updatedAt
            )
            from KnowledgeBase knowledgeBase
            where knowledgeBase.tenant.id = :tenantId
            order by knowledgeBase.id desc
            """)
    Slice<KnowledgeBaseSummaryProjection> findSupportSummariesByTenantId(
            @Param("tenantId") Long tenantId,
            Pageable pageable
    );

    @Query("""
            select new com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseSummaryProjection(
                knowledgeBase.id, knowledgeBase.name, knowledgeBase.description,
                knowledgeBase.enabled, knowledgeBase.updatedAt
            )
            from KnowledgeBase knowledgeBase
            where knowledgeBase.id = :id and knowledgeBase.tenant.id = :tenantId
            """)
    Optional<KnowledgeBaseSummaryProjection> findSupportSummaryByIdAndTenantId(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId
    );

    /**
     * 判断当前租户下是否已有同名知识库。
     */
    boolean existsByTenant_IdAndName(Long tenantId, String name);

    /**
     * 更新时排除自己判断是否重名。
     */
    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);
}
