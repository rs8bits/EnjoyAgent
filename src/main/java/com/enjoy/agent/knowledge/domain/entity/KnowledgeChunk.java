package com.enjoy.agent.knowledge.domain.entity;

import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 知识切片实体。
 * 向量列 embedding 由 JDBC 直接写入和检索，因此这里不映射该字段。
 */
@Entity
@Table(
        name = "knowledge_chunk",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_chunk_document_index", columnNames = {"document_id", "chunk_index"})
)
public class KnowledgeChunk extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public KnowledgeDocument getDocument() {
        return document;
    }

    public void setDocument(KnowledgeDocument document) {
        this.document = document;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
