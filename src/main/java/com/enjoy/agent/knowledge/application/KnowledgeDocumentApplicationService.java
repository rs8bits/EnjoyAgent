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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final KnowledgeIngestOutboxService knowledgeIngestOutboxService;
    private final KnowledgeIngestProperties knowledgeIngestProperties;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final DistributionSummary fileSizeSummary;
    private final DistributionSummary chunkCountSummary;

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
            KnowledgeIngestOutboxService knowledgeIngestOutboxService,
            KnowledgeIngestProperties knowledgeIngestProperties,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            Clock clock
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
        this.knowledgeIngestOutboxService = knowledgeIngestOutboxService;
        this.knowledgeIngestProperties = knowledgeIngestProperties;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.fileSizeSummary = DistributionSummary.builder("enjoy.document.ingest.file.size")
                .baseUnit("bytes")
                .description("Knowledge document upload size")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.chunkCountSummary = DistributionSummary.builder("enjoy.document.ingest.chunk.count")
                .description("Chunks produced by a knowledge document")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * 上传文档。异步模式只等待 MinIO 与 QUEUED/Outbox 原子入库。
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
        fileSizeSummary.record(fileBytes.length);
        if (knowledgeIngestProperties.isAsyncEnabled()) {
            return enqueueDocument(
                    knowledgeBase,
                    resolvedFilename,
                    contentType,
                    fileBytes
            );
        }

        Timer.Sample totalSample = Timer.start(meterRegistry);
        String outcome = "failure";
        StoredObjectInfo storedObject = null;
        Long documentId = null;
        MinioRollbackCompensation rollbackCompensation = null;

        try {
            storedObject = recordPhase("storage_upload", () ->
                    minioStorageService.uploadKnowledgeDocument(
                            knowledgeBaseId,
                            resolvedFilename,
                            contentType,
                            fileBytes
                    ));
            StoredObjectInfo uploadedObject = storedObject;
            rollbackCompensation = new MinioRollbackCompensation(uploadedObject);
            rollbackCompensation.registerIfTransactionActive();
            MinioRollbackCompensation transactionRollbackCompensation = rollbackCompensation;

            KnowledgeDocument document = requireTransactionResult(transactionTemplate.execute(status -> {
                transactionRollbackCompensation.registerIfTransactionActive();
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
                String text = recordPhase("text_extract", () ->
                        documentTextExtractor.extractText(resolvedFilename, contentType, fileBytes));
                if (text == null || text.isBlank()) {
                    throw new ApiException("DOCUMENT_TEXT_EMPTY", "Document text is empty after extraction", HttpStatus.BAD_REQUEST);
                }

                List<String> chunks = recordPhase("chunk", () -> documentChunker.chunk(text));
                if (chunks.isEmpty()) {
                    throw new ApiException("DOCUMENT_CHUNK_EMPTY", "Document cannot be split into valid chunks", HttpStatus.BAD_REQUEST);
                }
                chunkCountSummary.record(chunks.size());

                PreparedKnowledgeBase preparedKnowledgeBase = knowledgeBaseApplicationService.snapshotKnowledgeBase(knowledgeBase);
                List<float[]> embeddings = recordPhase("embedding", () ->
                        embeddingGatewayService.embedTexts(preparedKnowledgeBase.embeddingModelConfig(), chunks));

                List<KnowledgeChunkRow> rows = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    rows.add(new KnowledgeChunkRow(i, chunks.get(i), embeddings.get(i)));
                }

                List<KnowledgeChunk> persistedChunks = recordPhase("database_persist", () ->
                        requireTransactionResult(transactionTemplate.execute(status ->
                                persistDocumentChunks(document.getId(), rows)
                        )));
                recordPhase("search_index", () -> {
                    knowledgeSearchIndexer.indexDocumentChunks(document, persistedChunks, rows);
                    return null;
                });
                KnowledgeDocumentResponse response = requireTransactionResult(transactionTemplate.execute(status ->
                        markDocumentReady(document.getId(), rows.size())
                ));
                outcome = "success";
                return response;
            } catch (RuntimeException ex) {
                Long failedDocumentId = documentId;
                transactionTemplate.executeWithoutResult(status -> cleanupFailedDocumentProcessing(failedDocumentId));
                cleanupSearchIndexQuietly(failedDocumentId);
                throw ex;
            }
        } catch (ApiException ex) {
            if (storedObject != null && documentId == null) {
                rollbackCompensation.cleanupIfNotRegistered(null);
            }
            throw ex;
        } catch (Exception ex) {
            if (storedObject != null && documentId == null) {
                rollbackCompensation.cleanupIfNotRegistered(null);
            }
            throw new ApiException("DOCUMENT_UPLOAD_FAILED", "Failed to upload document", HttpStatus.BAD_REQUEST);
        } finally {
            totalSample.stop(Timer.builder("enjoy.document.ingest.total")
                    .description("Synchronous knowledge document ingestion time")
                    .tag("mode", "sync")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    public boolean isAsyncIngestEnabled() {
        return knowledgeIngestProperties.isAsyncEnabled();
    }

    /**
     * MinIO 不参与数据库事务；若文档与 Outbox 入库失败，尽力补偿已上传对象。
     */
    private KnowledgeDocumentResponse enqueueDocument(
            KnowledgeBase knowledgeBase,
            String resolvedFilename,
            String contentType,
            byte[] fileBytes
    ) {
        StoredObjectInfo storedObject = recordPhase("storage_upload", () ->
                minioStorageService.uploadKnowledgeDocument(
                        knowledgeBase.getId(),
                        resolvedFilename,
                        contentType,
                        fileBytes
                ));
        MinioRollbackCompensation rollbackCompensation = new MinioRollbackCompensation(storedObject);
        rollbackCompensation.registerIfTransactionActive();
        AtomicBoolean transactionReturned = new AtomicBoolean(false);
        try {
            KnowledgeDocument document = requireTransactionResult(transactionTemplate.execute(status -> {
                rollbackCompensation.registerIfTransactionActive();
                KnowledgeDocument queued = new KnowledgeDocument();
                queued.setTenant(knowledgeBase.getTenant());
                queued.setKnowledgeBase(knowledgeBase);
                queued.setFileName(resolvedFilename);
                queued.setContentType(contentType);
                queued.setFileSize((long) fileBytes.length);
                queued.setStorageBucket(storedObject.bucket());
                queued.setStorageObjectKey(storedObject.objectKey());
                queued.setStatus(KnowledgeDocumentStatus.QUEUED);
                queued.setChunkCount(0);
                queued.setProcessingAttempts(0);
                KnowledgeDocument saved = knowledgeDocumentRepository.saveAndFlush(queued);
                knowledgeIngestOutboxService.enqueue(saved.getId(), 1, Instant.now(clock));
                return saved;
            }));
            transactionReturned.set(true);
            return toResponse(document);
        } catch (RuntimeException ex) {
            if (!transactionReturned.get()) {
                rollbackCompensation.cleanupIfNotRegistered(null);
            }
            throw ex;
        }
    }

    /**
     * RabbitMQ Worker 执行一次文档处理。普通重复消息不会和正在执行的任务并发处理；
     * broker redelivery 可恢复在 ACK 前崩溃的任务。
     */
    public void processAsyncDocument(
            Long documentId,
            int deliveryAttempt,
            boolean redelivered,
            String processingToken
    ) {
        if (processingToken == null || processingToken.isBlank()) {
            throw new IllegalArgumentException("Knowledge ingest processing token is required");
        }
        AsyncWorkItem workItem = requireTransactionResult(transactionTemplate.execute(status ->
                claimAsyncWork(documentId, deliveryAttempt, redelivered, processingToken)
        ));
        if (!workItem.shouldProcess()) {
            return;
        }

        Timer.Sample totalSample = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            if (workItem.resumeIndexOnly()) {
                assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
                List<KnowledgeChunkSearchDocument> searchDocuments = recordPhase("database_load", () ->
                        knowledgeChunkJdbcRepository.findSearchDocumentsByDocumentId(documentId));
                requireIndexableChunks(searchDocuments);
                assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
                recordPhase("search_index", () -> {
                    knowledgeSearchIndexer.indexSearchDocuments(searchDocuments);
                    return null;
                });
                assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
                requireTransactionResult(transactionTemplate.execute(status ->
                        markAsyncDocumentReady(documentId, deliveryAttempt, workItem, searchDocuments.size())
                ));
                outcome = "success";
                return;
            }

            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            byte[] fileBytes = recordPhase("storage_download", () ->
                    minioStorageService.downloadObject(workItem.storageBucket(), workItem.storageObjectKey()));
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            String text = recordPhase("text_extract", () -> documentTextExtractor.extractText(
                    workItem.fileName(),
                    workItem.contentType(),
                    fileBytes
            ));
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            if (text == null || text.isBlank()) {
                throw new ApiException("DOCUMENT_TEXT_EMPTY", "Document text is empty after extraction", HttpStatus.BAD_REQUEST);
            }

            List<String> chunks = recordPhase("chunk", () -> documentChunker.chunk(text));
            if (chunks.isEmpty()) {
                throw new ApiException("DOCUMENT_CHUNK_EMPTY", "Document cannot be split into valid chunks", HttpStatus.BAD_REQUEST);
            }
            chunkCountSummary.record(chunks.size());

            KnowledgeBase knowledgeBase = knowledgeBaseApplicationService.requireRunnableKnowledgeBase(
                    workItem.tenantId(),
                    workItem.knowledgeBaseId()
            );
            PreparedKnowledgeBase preparedKnowledgeBase = knowledgeBaseApplicationService.snapshotKnowledgeBase(knowledgeBase);
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            List<float[]> embeddings = recordPhase("embedding", () ->
                    embeddingGatewayService.embedTexts(preparedKnowledgeBase.embeddingModelConfig(), chunks));
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);

            List<KnowledgeChunkRow> rows = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                rows.add(new KnowledgeChunkRow(i, chunks.get(i), embeddings.get(i)));
            }

            recordPhase("database_persist", () ->
                    requireTransactionResult(transactionTemplate.execute(status ->
                            persistAsyncDocumentChunks(documentId, deliveryAttempt, workItem, rows)
                    )));
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            List<KnowledgeChunkSearchDocument> searchDocuments = recordPhase("database_load", () ->
                    knowledgeChunkJdbcRepository.findSearchDocumentsByDocumentId(documentId));
            requireIndexableChunks(searchDocuments);
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            recordPhase("search_index", () -> {
                knowledgeSearchIndexer.indexSearchDocuments(searchDocuments);
                return null;
            });
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            requireTransactionResult(transactionTemplate.execute(status ->
                    markAsyncDocumentReady(documentId, deliveryAttempt, workItem, rows.size())
            ));
            outcome = "success";
        } finally {
            totalSample.stop(Timer.builder("enjoy.document.ingest.total")
                    .description("Knowledge document processing time")
                    .tag("mode", "async")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /**
     * 外部调用失败后，有限重试通过新的 Outbox 延迟投递；最终失败由 Listener 拒绝到 parking queue。
     */
    public KnowledgeIngestFailureDisposition handleAsyncProcessingFailure(
            Long documentId,
            int deliveryAttempt,
            String processingToken,
            RuntimeException failure
    ) {
        return requireTransactionResult(transactionTemplate.execute(status -> {
            KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId).orElse(null);
            if (document == null) {
                return KnowledgeIngestFailureDisposition.PARK;
            }
            int recordedAttempts = Objects.requireNonNullElse(document.getProcessingAttempts(), 0);
            if (document.getStatus() == KnowledgeDocumentStatus.FAILED) {
                return KnowledgeIngestFailureDisposition.PARK;
            }
            if (document.getStatus() == KnowledgeDocumentStatus.READY
                    || recordedAttempts > deliveryAttempt
                    || (document.getStatus() == KnowledgeDocumentStatus.RETRYING && recordedAttempts == deliveryAttempt)
                    || (document.getStatus() == KnowledgeDocumentStatus.INDEXING
                    && document.getNextRetryAt() != null
                    && recordedAttempts == deliveryAttempt)) {
                return KnowledgeIngestFailureDisposition.IGNORE;
            }
            if (!Objects.equals(document.getProcessingToken(), processingToken)) {
                boolean claimWasNotPersisted = document.getProcessingToken() == null
                        && deliveryAttempt == recordedAttempts + 1
                        && (document.getStatus() == KnowledgeDocumentStatus.QUEUED
                        || document.getStatus() == KnowledgeDocumentStatus.RETRYING
                        || (document.getStatus() == KnowledgeDocumentStatus.INDEXING
                        && document.getNextRetryAt() != null));
                if (claimWasNotPersisted) {
                    throw new IllegalStateException("Knowledge ingest claim was not persisted");
                }
                return KnowledgeIngestFailureDisposition.IGNORE;
            }

            document.setLastErrorCode(errorCode(failure));
            document.setLastErrorMessage(safeErrorMessage(failure));
            boolean terminal = isPermanentFailure(failure)
                    || knowledgeIngestProperties.isLastAttemptInCycle(deliveryAttempt);
            if (terminal) {
                document.setStatus(KnowledgeDocumentStatus.FAILED);
                document.setNextRetryAt(null);
                document.setCompletedAt(Instant.now(clock));
                clearProcessingOwnership(document);
                knowledgeDocumentRepository.save(document);
                return KnowledgeIngestFailureDisposition.PARK;
            }

            Instant retryAt = Instant.now(clock).plus(knowledgeIngestProperties.retryDelayForAttempt(deliveryAttempt));
            if (document.getStatus() != KnowledgeDocumentStatus.INDEXING) {
                document.setStatus(KnowledgeDocumentStatus.RETRYING);
            }
            document.setNextRetryAt(retryAt);
            clearProcessingOwnership(document);
            knowledgeDocumentRepository.save(document);
            knowledgeIngestOutboxService.enqueue(documentId, deliveryAttempt + 1, retryAt);
            return KnowledgeIngestFailureDisposition.RETRY_SCHEDULED;
        }));
    }

    /**
     * 手工重试一个已经进入 parking 状态的文档，开启新一轮有限重试。
     */
    public KnowledgeDocumentResponse retryFailedDocument(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument owned = requireTenantOwnedDocument(knowledgeBaseId, documentId);
        return requireTransactionResult(transactionTemplate.execute(status -> {
            KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(owned.getId())
                    .orElseThrow(() -> new ApiException(
                            "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                            "Knowledge document not found",
                            HttpStatus.NOT_FOUND
                    ));
            if (document.getStatus() != KnowledgeDocumentStatus.FAILED) {
                throw new ApiException(
                        "KNOWLEDGE_DOCUMENT_RETRY_NOT_ALLOWED",
                        "Only FAILED documents can be retried",
                        HttpStatus.CONFLICT
                );
            }
            int nextAttempt = Objects.requireNonNullElse(document.getProcessingAttempts(), 0) + 1;
            Instant retryAt = Instant.now(clock);
            document.setStatus(Objects.requireNonNullElse(document.getChunkCount(), 0) > 0
                    ? KnowledgeDocumentStatus.INDEXING
                    : KnowledgeDocumentStatus.RETRYING);
            document.setCompletedAt(null);
            document.setNextRetryAt(retryAt);
            document.setLastErrorCode(null);
            document.setLastErrorMessage(null);
            clearProcessingOwnership(document);
            KnowledgeDocument saved = knowledgeDocumentRepository.saveAndFlush(document);
            knowledgeIngestOutboxService.enqueue(documentId, nextAttempt, retryAt);
            return toResponse(saved);
        }));
    }

    /**
     * 查询知识库文档列表。
     */
    public Page<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId, Pageable pageable) {
        knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBaseId);
        return knowledgeDocumentRepository.findAllByKnowledgeBase_Id(knowledgeBaseId, pageable)
                .map(this::toResponse);
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
        if (isActiveProcessingStatus(document.getStatus())) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_PROCESSING",
                    "A queued or processing document cannot be deleted",
                    HttpStatus.CONFLICT
            );
        }
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

    private List<KnowledgeChunk> persistAsyncDocumentChunks(
            Long documentId,
            int deliveryAttempt,
            AsyncWorkItem workItem,
            List<KnowledgeChunkRow> rows
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                        "Knowledge document not found",
                        HttpStatus.NOT_FOUND
                ));
        assertAsyncWorkOwnership(document, deliveryAttempt, workItem);

        knowledgeChunkRepository.deleteAllByDocument_Id(documentId);
        knowledgeChunkRepository.flush();
        knowledgeChunkJdbcRepository.batchInsert(
                document.getTenant().getId(),
                document.getKnowledgeBase().getId(),
                document.getId(),
                rows
        );
        document.setStatus(KnowledgeDocumentStatus.INDEXING);
        document.setChunkCount(rows.size());
        document.setProcessingLeaseUntil(leaseUntil());
        knowledgeDocumentRepository.save(document);
        return knowledgeChunkRepository.findAllByDocument_IdOrderByChunkIndexAsc(documentId);
    }

    private AsyncWorkItem claimAsyncWork(
            Long documentId,
            int deliveryAttempt,
            boolean redelivered,
            String processingToken
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                        "Knowledge document not found",
                        HttpStatus.NOT_FOUND
                ));
        int recordedAttempts = Objects.requireNonNullElse(document.getProcessingAttempts(), 0);
        if (document.getStatus() == KnowledgeDocumentStatus.READY || recordedAttempts > deliveryAttempt) {
            return AsyncWorkItem.noop();
        }
        if (document.getStatus() == KnowledgeDocumentStatus.FAILED) {
            throw new KnowledgeIngestAlreadyFailedException(documentId);
        }

        KnowledgeDocumentStatus claimedFrom = document.getStatus();
        boolean scheduledIndexRetry = claimedFrom == KnowledgeDocumentStatus.INDEXING
                && document.getNextRetryAt() != null;
        boolean resumeIndexOnly = claimedFrom == KnowledgeDocumentStatus.INDEXING
                || (claimedFrom == KnowledgeDocumentStatus.RETRYING
                && Objects.requireNonNullElse(document.getChunkCount(), 0) > 0);
        if (scheduledIndexRetry) {
            if (deliveryAttempt != recordedAttempts + 1) {
                return AsyncWorkItem.noop();
            }
            document.setProcessingAttempts(deliveryAttempt);
        } else if (claimedFrom == KnowledgeDocumentStatus.PROCESSING
                || claimedFrom == KnowledgeDocumentStatus.INDEXING) {
            if (!redelivered || recordedAttempts != deliveryAttempt) {
                return AsyncWorkItem.noop();
            }
        } else {
            if (document.getStatus() != KnowledgeDocumentStatus.QUEUED
                    && document.getStatus() != KnowledgeDocumentStatus.RETRYING) {
                throw new ApiException(
                        "KNOWLEDGE_DOCUMENT_STATUS_INVALID",
                        "Knowledge document cannot start processing from current status",
                        HttpStatus.CONFLICT
                );
            }
            if (deliveryAttempt != recordedAttempts + 1) {
                return AsyncWorkItem.noop();
            }
            document.setProcessingAttempts(deliveryAttempt);
        }

        document.setStatus(resumeIndexOnly
                ? KnowledgeDocumentStatus.INDEXING
                : KnowledgeDocumentStatus.PROCESSING);
        if (document.getProcessingStartedAt() == null) {
            document.setProcessingStartedAt(Instant.now(clock));
        }
        document.setNextRetryAt(null);
        document.setLastErrorCode(null);
        document.setLastErrorMessage(null);
        document.setProcessingToken(processingToken);
        long processingFence = Objects.requireNonNullElse(document.getProcessingFence(), 0L) + 1L;
        document.setProcessingFence(processingFence);
        document.setProcessingLeaseUntil(leaseUntil());
        knowledgeDocumentRepository.saveAndFlush(document);
        return new AsyncWorkItem(
                true,
                resumeIndexOnly,
                processingToken,
                processingFence,
                document.getTenant().getId(),
                document.getKnowledgeBase().getId(),
                document.getFileName(),
                document.getContentType(),
                document.getStorageBucket(),
                document.getStorageObjectKey()
        );
    }

    private KnowledgeDocument markAsyncDocumentReady(
            Long documentId,
            int deliveryAttempt,
            AsyncWorkItem workItem,
            int chunkCount
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                        "Knowledge document not found",
                        HttpStatus.NOT_FOUND
                ));
        assertAsyncWorkOwnership(document, deliveryAttempt, workItem);
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setChunkCount(chunkCount);
        document.setCompletedAt(Instant.now(clock));
        document.setNextRetryAt(null);
        document.setLastErrorCode(null);
        document.setLastErrorMessage(null);
        clearProcessingOwnership(document);
        return knowledgeDocumentRepository.saveAndFlush(document);
    }

    private void assertAndRenewAsyncWorkOwnership(
            Long documentId,
            int deliveryAttempt,
            AsyncWorkItem workItem
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                    .orElseThrow(() -> new KnowledgeIngestWorkSupersededException(documentId, deliveryAttempt));
            assertAsyncWorkOwnership(document, deliveryAttempt, workItem);
            document.setProcessingLeaseUntil(leaseUntil());
            knowledgeDocumentRepository.save(document);
        });
    }

    private void assertAsyncWorkOwnership(
            KnowledgeDocument document,
            int deliveryAttempt,
            AsyncWorkItem workItem
    ) {
        if (!Objects.equals(document.getProcessingAttempts(), deliveryAttempt)
                || !Objects.equals(document.getProcessingToken(), workItem.processingToken())
                || !Objects.equals(document.getProcessingFence(), workItem.processingFence())
                || (document.getStatus() != KnowledgeDocumentStatus.PROCESSING
                && document.getStatus() != KnowledgeDocumentStatus.INDEXING)) {
            throw new KnowledgeIngestWorkSupersededException(document.getId(), deliveryAttempt);
        }
    }

    private Instant leaseUntil() {
        return Instant.now(clock).plus(knowledgeIngestProperties.getProcessingLeaseDuration());
    }

    private void clearProcessingOwnership(KnowledgeDocument document) {
        document.setProcessingToken(null);
        document.setProcessingLeaseUntil(null);
    }

    private void requireIndexableChunks(List<KnowledgeChunkSearchDocument> searchDocuments) {
        if (searchDocuments.isEmpty()) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_CHUNKS_EMPTY",
                    "Knowledge document does not contain indexable chunks",
                    HttpStatus.CONFLICT
            );
        }
    }

    /**
     * 在索引完成后把文档标记为 READY。
     */
    private KnowledgeDocumentResponse markDocumentReady(Long documentId, int chunkCount) {
        KnowledgeDocument document = knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setChunkCount(chunkCount);
        document.setCompletedAt(Instant.now(clock));
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
     * 在数据库事务回滚或文档删除失败补偿时尽力删除 MinIO 对象。
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

    private <T> T recordPhase(String phase, Supplier<T> action) {
        return Timer.builder("enjoy.document.ingest.phase")
                .description("Knowledge document ingestion phase time")
                .tag("phase", phase)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(action);
    }

    private boolean isPermanentFailure(RuntimeException failure) {
        return failure instanceof ApiException apiException
                && apiException.getStatus().is4xxClientError()
                && apiException.getStatus() != HttpStatus.TOO_MANY_REQUESTS;
    }

    private String errorCode(RuntimeException failure) {
        if (failure instanceof ApiException apiException) {
            return truncate(apiException.getCode(), 64);
        }
        return "DOCUMENT_PROCESSING_FAILED";
    }

    private String safeErrorMessage(RuntimeException failure) {
        if (failure instanceof ApiException apiException) {
            return truncate(apiException.getMessage(), 1000);
        }
        return "Document processing failed";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isActiveProcessingStatus(KnowledgeDocumentStatus status) {
        return status == KnowledgeDocumentStatus.QUEUED
                || status == KnowledgeDocumentStatus.PROCESSING
                || status == KnowledgeDocumentStatus.RETRYING
                || status == KnowledgeDocumentStatus.INDEXING;
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
                document.getProcessingAttempts(),
                document.getNextRetryAt(),
                document.getLastErrorCode(),
                document.getLastErrorMessage(),
                document.getProcessingStartedAt(),
                document.getCompletedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    private final class MinioRollbackCompensation {

        private final StoredObjectInfo storedObject;
        private final AtomicBoolean registered = new AtomicBoolean(false);
        private final AtomicBoolean cleaned = new AtomicBoolean(false);

        private MinioRollbackCompensation(StoredObjectInfo storedObject) {
            this.storedObject = storedObject;
        }

        private void registerIfTransactionActive() {
            if (!TransactionSynchronizationManager.isSynchronizationActive()
                    || !registered.compareAndSet(false, true)) {
                return;
            }
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            cleanupOnce(null);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                registered.set(false);
                throw ex;
            }
        }

        private void cleanupIfNotRegistered(Long documentId) {
            if (!registered.get()) {
                cleanupOnce(documentId);
            }
        }

        private void cleanupOnce(Long documentId) {
            if (cleaned.compareAndSet(false, true)) {
                cleanupUploadedObjectQuietly(storedObject, documentId);
            }
        }
    }

    private record AsyncWorkItem(
            boolean shouldProcess,
            boolean resumeIndexOnly,
            String processingToken,
            Long processingFence,
            Long tenantId,
            Long knowledgeBaseId,
            String fileName,
            String contentType,
            String storageBucket,
            String storageObjectKey
    ) {
        private static AsyncWorkItem noop() {
            return new AsyncWorkItem(false, false, null, null, null, null, null, null, null, null);
        }
    }
}
