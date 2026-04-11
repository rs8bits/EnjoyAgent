package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识切片仓储接口。
 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /**
     * 判断某个知识库下是否已经存在可检索的切片。
     */
    boolean existsByKnowledgeBase_Id(Long knowledgeBaseId);

    /**
     * 删除某个文档下的全部切片。
     */
    void deleteAllByDocument_Id(Long documentId);

    /**
     * 查询某个文档下的全部切片。
     */
    List<KnowledgeChunk> findAllByDocument_IdOrderByChunkIndexAsc(Long documentId);
}
