package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * 知识库文档仓储接口。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /**
     * 查询某个知识库下的文档列表。
     */
    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    List<KnowledgeDocument> findAllByKnowledgeBase_IdOrderByIdDesc(Long knowledgeBaseId);

    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    Page<KnowledgeDocument> findAllByKnowledgeBase_IdAndStatusNot(
            Long knowledgeBaseId,
            com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus status,
            Pageable pageable
    );

    /**
     * 查询当前租户、当前知识库下的单个文档。
     */
    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    Optional<KnowledgeDocument> findByIdAndKnowledgeBase_IdAndTenant_Id(Long id, Long knowledgeBaseId, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from KnowledgeDocument document where document.id = :id")
    Optional<KnowledgeDocument> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    @Query("""
            select document from KnowledgeDocument document
            where document.id = :id
              and document.knowledgeBase.id = :knowledgeBaseId
              and document.tenant.id = :tenantId
            """)
    Optional<KnowledgeDocument> findOwnedByIdForUpdate(
            @Param("id") Long id,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("tenantId") Long tenantId
    );

    @Query(value = """
            SELECT *
            FROM knowledge_document
            WHERE status IN ('PROCESSING', 'INDEXING')
              AND processing_lease_until IS NOT NULL
              AND processing_lease_until <= :now
            ORDER BY processing_lease_until ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batch_size
            """, nativeQuery = true)
    List<KnowledgeDocument> findExpiredProcessingLeasesForUpdate(
            @Param("now") Instant now,
            @Param("batch_size") int batchSize
    );

    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    @Query("select document from KnowledgeDocument document where document.id = :id")
    Optional<KnowledgeDocument> findByIdWithRelations(@Param("id") Long id);

    /**
     * 判断知识库下是否还有文档。
     */
    boolean existsByKnowledgeBase_Id(Long knowledgeBaseId);

    @Query("""
            select document.id from KnowledgeDocument document
            where document.knowledgeBase.id = :knowledgeBaseId
              and document.status = com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus.READY
              and document.id in :documentIds
            """)
    List<Long> findReadyDocumentIds(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentIds") List<Long> documentIds
    );

    @EntityGraph(attributePaths = {"tenant", "knowledgeBase"})
    Optional<KnowledgeDocument> findByTenant_IdAndKnowledgeBase_IdAndContentSha256(
            Long tenantId,
            Long knowledgeBaseId,
            String contentSha256
    );

    @Query(value = "SELECT id FROM tenant WHERE id = :tenantId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockTenantForImport(@Param("tenantId") Long tenantId);

    @Query(value = """
            SELECT COUNT(*)
            FROM knowledge_document
            WHERE tenant_id = :tenantId
              AND status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'INDEXING')
            """, nativeQuery = true)
    long countActiveImportsByTenant(@Param("tenantId") Long tenantId);

    @Query(value = """
            SELECT COUNT(*)
            FROM knowledge_document
            WHERE tenant_id = :tenantId AND status <> 'DELETING'
            """, nativeQuery = true)
    long countQuotaDocumentsByTenant(@Param("tenantId") Long tenantId);

    @Query(value = """
            SELECT COALESCE(SUM(file_size), 0)
            FROM knowledge_document
            WHERE tenant_id = :tenantId AND status <> 'DELETING'
            """, nativeQuery = true)
    long sumQuotaSourceBytesByTenant(@Param("tenantId") Long tenantId);

    @Query(value = """
            SELECT COALESCE(SUM(chunk_count), 0)
            FROM knowledge_document
            WHERE tenant_id = :tenantId
              AND id <> :documentId
              AND status <> 'DELETING'
            """, nativeQuery = true)
    long sumQuotaChunksByTenantExcludingDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );
}
