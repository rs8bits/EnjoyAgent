package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeChunk;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Elasticsearch 知识切片索引器。
 */
@Service
public class KnowledgeSearchIndexer {

    private final KnowledgeSearchGateway knowledgeSearchGateway;
    private final KnowledgeSearchProperties searchProperties;

    public KnowledgeSearchIndexer(
            KnowledgeSearchGateway knowledgeSearchGateway,
            KnowledgeSearchProperties searchProperties
    ) {
        this.knowledgeSearchGateway = knowledgeSearchGateway;
        this.searchProperties = searchProperties;
    }

    public boolean isEnabled() {
        return knowledgeSearchGateway.isEnabled();
    }

    /**
     * 把一个文档的全部切片同步到 Elasticsearch。
     */
    public void indexDocumentChunks(KnowledgeDocument document, List<KnowledgeChunk> persistedChunks, List<KnowledgeChunkRow> rows) {
        if (!knowledgeSearchGateway.isEnabled()) {
            return;
        }
        Map<Integer, KnowledgeChunkRow> rowByChunkIndex = rows.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeChunkRow::chunkIndex, Function.identity()));
        List<KnowledgeChunkSearchDocument> documents = persistedChunks.stream()
                .map(chunk -> {
                    KnowledgeChunkRow row = rowByChunkIndex.get(chunk.getChunkIndex());
                    if (row == null) {
                        throw new IllegalStateException("Chunk embedding row missing for chunkIndex=" + chunk.getChunkIndex());
                    }
                    return new KnowledgeChunkSearchDocument(
                            chunk.getId(),
                            document.getTenant().getId(),
                            document.getKnowledgeBase().getId(),
                            document.getId(),
                            document.getFileName(),
                            chunk.getChunkIndex(),
                            chunk.getContent(),
                            row.embedding(),
                            chunk.getCreatedAt(),
                            chunk.getUpdatedAt()
                    );
                })
                .toList();
        indexSearchDocuments(documents);
    }

    /**
     * 直接把检索文档批量写入 Elasticsearch。
     */
    public void indexSearchDocuments(List<KnowledgeChunkSearchDocument> documents) {
        if (!knowledgeSearchGateway.isEnabled()) {
            return;
        }
        int batchSize = bulkBatchSize();
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(start + batchSize, documents.size());
            knowledgeSearchGateway.bulkUpsert(documents.subList(start, end));
        }
    }

    public int bulkBatchSize() {
        return searchProperties.getBulkBatchSize();
    }

    /**
     * 删除文档在 Elasticsearch 中的全部切片索引。
     */
    public void deleteDocumentChunks(Long documentId) {
        knowledgeSearchGateway.deleteByDocumentId(documentId);
    }
}
