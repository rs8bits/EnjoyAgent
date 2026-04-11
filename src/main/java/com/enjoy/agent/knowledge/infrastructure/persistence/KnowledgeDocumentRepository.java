package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库文档仓储接口。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /**
     * 查询某个知识库下的文档列表。
     */
    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    List<KnowledgeDocument> findAllByKnowledgeBase_IdOrderByIdDesc(Long knowledgeBaseId);

    /**
     * 查询当前租户、当前知识库下的单个文档。
     */
    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    Optional<KnowledgeDocument> findByIdAndKnowledgeBase_IdAndTenant_Id(Long id, Long knowledgeBaseId, Long tenantId);

    /**
     * 判断知识库下是否还有文档。
     */
    boolean existsByKnowledgeBase_Id(Long knowledgeBaseId);
}
