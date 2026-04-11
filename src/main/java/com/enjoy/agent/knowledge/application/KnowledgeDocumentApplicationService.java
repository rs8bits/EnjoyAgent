package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.api.response.KnowledgeDocumentResponse;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeChunk;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkJdbcRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档应用服务。
 */
@Service
public class KnowledgeDocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentApplicationService.class);

    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeChunkJdbcRepository knowledgeChunkJdbcRepository;
    private final KnowledgeSearchIndexer knowledgeSearchIndexer;
    private final MinioStorageService minioStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentChunker documentChunker;
    private final EmbeddingGatewayService embeddingGatewayService;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeDocumentApplicationService(
            KnowledgeBaseApplicationService knowledgeBaseApplicationService,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeChunkRepository knowledgeChunkRepository,
            KnowledgeChunkJdbcRepository knowledgeChunkJdbcRepository,
            KnowledgeSearchIndexer knowledgeSearchIndexer,
            MinioStorageService minioStorageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentChunker documentChunker,
            EmbeddingGatewayService embeddingGatewayService,
            TransactionTemplate transactionTemplate
    ) {
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.knowledgeChunkJdbcRepository = knowledgeChunkJdbcRepository;
        this.knowledgeSearchIndexer = knowledgeSearchIndexer;
        this.minioStorageService = minioStorageService;
        this.documentTextExtractor = documentTextExtractor;
        this.documentChunker = documentChunker;
        this.embeddingGatewayService = embeddingGatewayService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 上传文档，并同步完成解析、切片与向量化。
     */
    public KnowledgeDocumentResponse uploadDocument(Long knowledgeBaseId, MultipartFile file) {
        validateFile(file);
        try {
            return uploadDocumentFromBytes(
                    knowledgeBaseId,
                    Objects.requireNonNullElse(file.getOriginalFilename(), "document.bin"),
                    file.getContentType(),
                    file.getBytes()
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("DOCUMENT_UPLOAD_FAILED", "Failed to upload document", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 使用已有文件字节导入文档，并同步完成解析、切片与向量化。
     */
    public KnowledgeDocumentResponse uploadDocumentFromBytes(
            Long knowledgeBaseId,
            String originalFilename,
            String contentType,
            byte[] fileBytes
    ) {
        CurrentUserContext.requireCurrentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBaseId);

        String resolvedFilename = Objects.requireNonNullElse(originalFilename, "document.bin");
        StoredObjectInfo storedObject = null;
        Long documentId = null;

        try {
            storedObject = minioStorageService.uploadKnowledgeDocument(
                    knowledgeBaseId,
                    resolvedFilename,
                    contentType,
                    fileBytes
            );
            StoredObjectInfo uploadedObject = storedObject;

            KnowledgeDocument document = requireTransactionResult(transactionTemplate.execute(status -> {
                KnowledgeDocument knowledgeDocument = new KnowledgeDocument();
                knowledgeDocument.setTenant(knowledgeBase.getTenant());
                knowledgeDocument.setKnowledgeBase(knowledgeBase);
                knowledgeDocument.setFileName(resolvedFilename);
                knowledgeDocument.setContentType(contentType);
                knowledgeDocument.setFileSize((long) fileBytes.length);
                knowledgeDocument.setStorageBucket(uploadedObject.bucket());
                knowledgeDocument.setStorageObjectKey(uploadedObject.objectKey());
                knowledgeDocument.setStatus(KnowledgeDocumentStatus.PROCESSING);
                knowledgeDocument.setChunkCount(0);
                return knowledgeDocumentRepository.saveAndFlush(knowledgeDocument);
            }));
            documentId = document.getId();

            try {
                String text = documentTextExtractor.extractText(resolvedFilename, contentType, fileBytes);
                if (text == null || text.isBlank()) {
                    throw new ApiException("DOCUMENT_TEXT_EMPTY", "Document text is empty after extraction", HttpStatus.BAD_REQUEST);
                }

                List<String> chunks = documentChunker.chunk(text);
                if (chunks.isEmpty()) {
                    throw new ApiException("DOCUMENT_CHUNK_EMPTY", "Document cannot be split into valid chunks", HttpStatus.BAD_REQUEST);
                }

                PreparedKnowledgeBase preparedKnowledgeBase = knowledgeBaseApplicationService.snapshotKnowledgeBase(knowledgeBase);
                List<float[]> embeddings = embeddingGatewayService.embedTexts(preparedKnowledgeBase.embeddingModelConfig(), chunks);

                List<KnowledgeChunkRow> rows = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    rows.add(new KnowledgeChunkRow(i, chunks.get(i), embeddings.get(i)));
                }

                List<KnowledgeChunk> persistedChunks = requireTransactionResult(transactionTemplate.execute(status ->
                        persistDocumentChunks(document.getId(), rows)
                ));
                knowledgeSearchIndexer.indexDocumentChunks(document, persistedChunks, rows);
                return requireTransactionResult(transactionTemplate.execute(status ->
                        markDocumentReady(document.getId(), rows.size())
                ));
            } catch (RuntimeException ex) {
                Long failedDocumentId = documentId;
                transactionTemplate.executeWithoutResult(status -> cleanupFailedDocumentProcessing(failedDocumentId));
                cleanupSearchIndexQuietly(failedDocumentId);
                cleanupUploadedObjectQuietly(storedObject, failedDocumentId);
                throw ex;
            }
        } catch (ApiException ex) {
            if (storedObject != null && documentId == null) {
                cleanupUploadedObjectQuietly(storedObject, null);
            }
            throw ex;
        } catch (Exception ex) {
            if (storedObject != null) {
                cleanupUploadedObjectQuietly(storedObject, documentId);
            }
            throw new ApiException("DOCUMENT_UPLOAD_FAILED", "Failed to upload document", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 查询知识库文档列表。
     */
    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) {
        knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBaseId);
        return knowledgeDocumentRepository.findAllByKnowledgeBase_IdOrderByIdDesc(knowledgeBaseId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询知识库文档详情。
     */
    public KnowledgeDocumentResponse getDocument(Long knowledgeBaseId, Long documentId) {
        return toResponse(requireTenantOwnedDocument(knowledgeBaseId, documentId));
    }

    /**
     * 为已有文档重建 Elasticsearch 检索索引。
     */
    public KnowledgeDocumentResponse reindexDocument(Long knowledgeBaseId, Long documentId) {
        if (!knowledgeSearchIndexer.isEnabled()) {
            throw new ApiException(
                    "KNOWLEDGE_SEARCH_DISABLED",
                    "Elasticsearch hybrid search is not enabled",
                    HttpStatus.BAD_REQUEST
            );
        }
        KnowledgeDocument document = requireTenantOwnedDocument(knowledgeBaseId, documentId);
        if (document.getStatus() != KnowledgeDocumentStatus.READY) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_NOT_READY",
                    "Only READY documents can rebuild search index",
                    HttpStatus.BAD_REQUEST
            );
        }
        List<KnowledgeChunkSearchDocument> searchDocuments =
                knowledgeChunkJdbcRepository.findSearchDocumentsByDocumentId(document.getId());
        if (searchDocuments.isEmpty()) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_CHUNKS_EMPTY",
                    "Knowledge document does not contain indexable chunks",
                    HttpStatus.BAD_REQUEST
            );
        }
        knowledgeSearchIndexer.indexSearchDocuments(searchDocuments);
        return toResponse(document);
    }

    /**
     * 删除知识库文档。
     */
    public void deleteDocument(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = requireTenantOwnedDocument(knowledgeBaseId, documentId);
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeChunkRepository.deleteAllByDocument_Id(document.getId());
            knowledgeDocumentRepository.delete(document);
        });
        cleanupSearchIndexQuietly(document.getId());
        minioStorageService.removeObject(document.getStorageBucket(), document.getStorageObjectKey());
    }

    /**
     * 在事务内写入切片。
     */
    private List<KnowledgeChunk> persistDocumentChunks(Long documentId, List<KnowledgeChunkRow> rows) {
        KnowledgeDocument document = knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));

        knowledgeChunkJdbcRepository.batchInsert(
                document.getTenant().getId(),
                document.getKnowledgeBase().getId(),
                document.getId(),
                rows
        );
        return knowledgeChunkRepository.findAllByDocument_IdOrderByChunkIndexAsc(documentId);
    }

    /**
     * 在索引完成后把文档标记为 READY。
     */
    private KnowledgeDocumentResponse markDocumentReady(Long documentId, int chunkCount) {
        KnowledgeDocument document = knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setChunkCount(chunkCount);
        return toResponse(knowledgeDocumentRepository.saveAndFlush(document));
    }

    /**
     * 标记文档处理失败。
     */
    private void markDocumentFailed(Long documentId) {
        knowledgeDocumentRepository.findById(documentId).ifPresent(document -> {
            document.setStatus(KnowledgeDocumentStatus.FAILED);
            knowledgeDocumentRepository.save(document);
        });
    }

    /**
     * 处理文档切片或建索引失败后的数据库回滚。
     */
    private void cleanupFailedDocumentProcessing(Long documentId) {
        knowledgeChunkRepository.deleteAllByDocument_Id(documentId);
        markDocumentFailed(documentId);
    }

    /**
     * 在处理失败后尽力删除已上传的 MinIO 对象，避免留下孤儿文件。
     */
    private void cleanupUploadedObjectQuietly(StoredObjectInfo storedObject, Long documentId) {
        if (storedObject == null) {
            return;
        }
        try {
            minioStorageService.removeObject(storedObject.bucket(), storedObject.objectKey());
        } catch (ApiException cleanupEx) {
            log.warn(
                    "清理失败的知识库上传对象时出错，documentId={}, bucket={}, objectKey={}, code={}",
                    documentId,
                    storedObject.bucket(),
                    storedObject.objectKey(),
                    cleanupEx.getCode()
            );
        }
    }

    /**
     * 在文档删除或索引失败时尽力清理 Elasticsearch 索引。
     */
    private void cleanupSearchIndexQuietly(Long documentId) {
        try {
            knowledgeSearchIndexer.deleteDocumentChunks(documentId);
        } catch (ApiException cleanupEx) {
            log.warn("清理知识检索索引时出错，documentId={}, code={}", documentId, cleanupEx.getCode());
        }
    }

    /**
     * 限制只能访问当前租户、当前知识库下的文档。
     */
    private KnowledgeDocument requireTenantOwnedDocument(Long knowledgeBaseId, Long documentId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return knowledgeDocumentRepository.findByIdAndKnowledgeBase_IdAndTenant_Id(documentId, knowledgeBaseId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 上传前校验文件。
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 把事务模板返回值统一做非空保护。
     */
    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction result must not be null");
    }

    /**
     * 转换成接口返回对象。
     */
    private KnowledgeDocumentResponse toResponse(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.getId(),
                document.getKnowledgeBase().getId(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
