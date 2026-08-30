package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.api.response.KnowledgeDocumentResponse;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkJdbcRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final KnowledgeDocumentCleanupService knowledgeDocumentCleanupService;
    private final KnowledgeIngestProperties knowledgeIngestProperties;
    private final KnowledgeProperties knowledgeProperties;
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
            KnowledgeDocumentCleanupService knowledgeDocumentCleanupService,
            KnowledgeIngestProperties knowledgeIngestProperties,
            KnowledgeProperties knowledgeProperties,
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
        this.knowledgeDocumentCleanupService = knowledgeDocumentCleanupService;
        this.knowledgeIngestProperties = knowledgeIngestProperties;
        this.knowledgeProperties = knowledgeProperties;
        if (knowledgeProperties.getExtractionTimeout()
                .compareTo(knowledgeIngestProperties.getProcessingLeaseDuration()) >= 0) {
            throw new IllegalArgumentException(
                    "Knowledge extraction timeout must be shorter than the processing lease duration"
            );
        }
        if (knowledgeProperties.getEmbeddingRequestTimeout()
                .compareTo(knowledgeIngestProperties.getProcessingLeaseDuration()) >= 0) {
            throw new IllegalArgumentException(
                    "Knowledge embedding request timeout must be shorter than the processing lease duration"
            );
        }
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
        validateFileSize(file.getSize());
        try {
            if (knowledgeIngestProperties.isAsyncEnabled()) {
                CurrentUserContext.requireCurrentUser();
                KnowledgeBase knowledgeBase = knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBaseId);
                String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "document.bin");
                fileSizeSummary.record(file.getSize());
                StoredObjectInfo storedObject;
                MessageDigest digest = sha256Digest();
                try (InputStream inputStream = file.getInputStream();
                     DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                    storedObject = recordPhase("storage_upload", () -> minioStorageService.uploadKnowledgeDocument(
                            knowledgeBaseId,
                            fileName,
                            file.getContentType(),
                            digestInputStream,
                            file.getSize()
                    ));
                }
                return enqueueStoredDocument(
                        knowledgeBase,
                        fileName,
                        file.getContentType(),
                        file.getSize(),
                        HexFormat.of().formatHex(digest.digest()),
                        storedObject
                );
            }
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
        if (fileBytes == null) {
            throw new ApiException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
        validateFileSize(fileBytes.length);
        CurrentUserContext.requireCurrentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBaseId);
        String contentSha256 = sha256(fileBytes);

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

            SyncCreateResult createResult = requireTransactionResult(transactionTemplate.execute(status -> {
                transactionRollbackCompensation.registerIfTransactionActive();
                knowledgeDocumentRepository.lockTenantForImport(knowledgeBase.getTenant().getId());
                KnowledgeDocument duplicate = knowledgeDocumentRepository
                        .findByTenant_IdAndKnowledgeBase_IdAndContentSha256(
                                knowledgeBase.getTenant().getId(), knowledgeBaseId, contentSha256)
                        .orElse(null);
                if (duplicate != null) {
                    requireDuplicateNotDeleting(duplicate);
                    return new SyncCreateResult(duplicate, true);
                }
                requireTenantImportCapacity(knowledgeBase.getTenant().getId(), fileBytes.length);
                KnowledgeDocument knowledgeDocument = new KnowledgeDocument();
                knowledgeDocument.setTenant(knowledgeBase.getTenant());
                knowledgeDocument.setKnowledgeBase(knowledgeBase);
                knowledgeDocument.setFileName(resolvedFilename);
                knowledgeDocument.setContentType(contentType);
                knowledgeDocument.setFileSize((long) fileBytes.length);
                knowledgeDocument.setStorageBucket(uploadedObject.bucket());
                knowledgeDocument.setStorageObjectKey(uploadedObject.objectKey());
                knowledgeDocument.setContentSha256(contentSha256);
                knowledgeDocument.setStatus(KnowledgeDocumentStatus.PROCESSING);
                knowledgeDocument.setChunkCount(0);
                return new SyncCreateResult(
                        knowledgeDocumentRepository.saveAndFlush(knowledgeDocument),
                        false
                );
            }));
            if (createResult.duplicate()) {
                rollbackCompensation.cleanupOnce(createResult.document().getId());
                outcome = "duplicate";
                return toResponse(createResult.document());
            }
            KnowledgeDocument document = createResult.document();
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

                reserveSynchronousChunkQuota(
                        document.getId(),
                        knowledgeBase.getTenant().getId(),
                        chunks.size()
                );
                PreparedKnowledgeBase preparedKnowledgeBase = knowledgeBaseApplicationService.snapshotKnowledgeBase(knowledgeBase);
                recordPhase("embedding_database_persist", () -> {
                    embedAndPersistChunkBatches(
                            document.getId(),
                            knowledgeBase.getTenant().getId(),
                            knowledgeBase.getId(),
                            chunks,
                            preparedKnowledgeBase.embeddingModelConfig(),
                            null
                    );
                    return null;
                });
                if (knowledgeSearchIndexer.isEnabled()) {
                    recordPhase("search_index", () -> indexDocumentInPages(document.getId()));
                }
                KnowledgeDocumentResponse response = requireTransactionResult(transactionTemplate.execute(status ->
                        markDocumentReady(document.getId(), chunks.size())
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
        return enqueueStoredDocument(
                knowledgeBase,
                resolvedFilename,
                contentType,
                fileBytes.length,
                sha256(fileBytes),
                storedObject
        );
    }

    private KnowledgeDocumentResponse enqueueStoredDocument(
            KnowledgeBase knowledgeBase,
            String resolvedFilename,
            String contentType,
            long fileSize,
            String contentSha256,
            StoredObjectInfo storedObject
    ) {
        MinioRollbackCompensation rollbackCompensation = new MinioRollbackCompensation(storedObject);
        rollbackCompensation.registerIfTransactionActive();
        AtomicBoolean transactionReturned = new AtomicBoolean(false);
        try {
            EnqueueResult result = requireTransactionResult(transactionTemplate.execute(status -> {
                rollbackCompensation.registerIfTransactionActive();
                knowledgeDocumentRepository.lockTenantForImport(knowledgeBase.getTenant().getId());
                KnowledgeDocument duplicate = knowledgeDocumentRepository
                        .findByTenant_IdAndKnowledgeBase_IdAndContentSha256(
                                knowledgeBase.getTenant().getId(),
                                knowledgeBase.getId(),
                                contentSha256
                        )
                        .orElse(null);
                if (duplicate != null) {
                    requireDuplicateNotDeleting(duplicate);
                    return new EnqueueResult(duplicate, true);
                }
                requireTenantImportCapacity(knowledgeBase.getTenant().getId(), fileSize);
                KnowledgeDocument queued = new KnowledgeDocument();
                queued.setTenant(knowledgeBase.getTenant());
                queued.setKnowledgeBase(knowledgeBase);
                queued.setFileName(resolvedFilename);
                queued.setContentType(contentType);
                queued.setFileSize(fileSize);
                queued.setStorageBucket(storedObject.bucket());
                queued.setStorageObjectKey(storedObject.objectKey());
                queued.setContentSha256(contentSha256);
                queued.setStatus(KnowledgeDocumentStatus.QUEUED);
                queued.setChunkCount(0);
                queued.setProcessingAttempts(0);
                KnowledgeDocument saved = knowledgeDocumentRepository.saveAndFlush(queued);
                knowledgeIngestOutboxService.enqueue(saved.getId(), 1, Instant.now(clock));
                return new EnqueueResult(saved, false);
            }));
            transactionReturned.set(true);
            if (result.duplicate()) {
                rollbackCompensation.cleanupOnce(result.document().getId());
            }
            return toResponse(result.document());
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
                int indexedChunks = knowledgeSearchIndexer.isEnabled()
                        ? recordPhase("search_index", () -> indexAsyncDocumentInPages(
                                documentId,
                                deliveryAttempt,
                                workItem
                        ))
                        : workItem.chunkCount();
                assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
                requireTransactionResult(transactionTemplate.execute(status ->
                        markAsyncDocumentReady(documentId, deliveryAttempt, workItem, indexedChunks)
                ));
                outcome = "success";
                return;
            }

            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            validateFileSize(workItem.fileSize());
            String text = recordPhase("storage_download_text_extract", () -> minioStorageService.readObject(
                    workItem.storageBucket(),
                    workItem.storageObjectKey(),
                    stream -> {
                        assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
                        return documentTextExtractor.extractText(workItem.fileName(), workItem.contentType(), stream);
                    }
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
            reserveAsyncChunkQuota(documentId, deliveryAttempt, workItem, chunks.size());
            recordPhase("embedding_database_persist", () -> {
                embedAndPersistChunkBatches(
                        documentId,
                        workItem.tenantId(),
                        workItem.knowledgeBaseId(),
                        chunks,
                        preparedKnowledgeBase.embeddingModelConfig(),
                        new AsyncBatchOwnership(deliveryAttempt, workItem)
                );
                return null;
            });
            requireTransactionResult(transactionTemplate.execute(status ->
                    markAsyncDocumentIndexing(documentId, deliveryAttempt, workItem)
            ));
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            if (knowledgeSearchIndexer.isEnabled()) {
                recordPhase("search_index", () -> indexAsyncDocumentInPages(documentId, deliveryAttempt, workItem));
            }
            assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem);
            requireTransactionResult(transactionTemplate.execute(status ->
                    markAsyncDocumentReady(documentId, deliveryAttempt, workItem, chunks.size())
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
     * Reclaims work whose worker disappeared without ACK/failure handling. Fencing makes any late worker stale.
     */
    public int reapExpiredProcessingLeases() {
        Integer recovered = transactionTemplate.execute(status -> {
            Instant now = Instant.now(clock);
            List<KnowledgeDocument> expired = knowledgeDocumentRepository.findExpiredProcessingLeasesForUpdate(
                    now,
                    knowledgeIngestProperties.getLeaseReaperBatchSize()
            );
            for (KnowledgeDocument document : expired) {
                int completedAttempt = Objects.requireNonNullElse(document.getProcessingAttempts(), 0);
                document.setLastErrorCode("PROCESSING_LEASE_EXPIRED");
                document.setLastErrorMessage("Document worker lease expired before completion");
                document.setProcessingFence(Objects.requireNonNullElse(document.getProcessingFence(), 0L) + 1L);
                clearProcessingOwnership(document);
                if (knowledgeIngestProperties.isLastAttemptInCycle(completedAttempt)) {
                    document.setStatus(KnowledgeDocumentStatus.FAILED);
                    document.setNextRetryAt(null);
                    document.setCompletedAt(now);
                } else {
                    int nextAttempt = completedAttempt + 1;
                document.setStatus(document.getStatus() == KnowledgeDocumentStatus.INDEXING
                        ? KnowledgeDocumentStatus.INDEXING
                        : KnowledgeDocumentStatus.RETRYING);
                    document.setNextRetryAt(now);
                    knowledgeIngestOutboxService.enqueue(document.getId(), nextAttempt, now);
                }
                knowledgeDocumentRepository.save(document);
            }
            return expired.size();
        });
        return Objects.requireNonNullElse(recovered, 0);
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
        return knowledgeDocumentRepository.findAllByKnowledgeBase_IdAndStatusNot(
                        knowledgeBaseId,
                        KnowledgeDocumentStatus.DELETING,
                        pageable
                )
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
        int indexedChunks = indexDocumentInPages(document.getId());
        if (indexedChunks == 0) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_CHUNKS_EMPTY",
                    "Knowledge document does not contain indexable chunks",
                    HttpStatus.BAD_REQUEST
            );
        }
        return toResponse(document);
    }

    /**
     * 删除知识库文档。
     */
    public void deleteDocument(Long knowledgeBaseId, Long documentId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocument document = knowledgeDocumentRepository.findOwnedByIdForUpdate(
                            documentId,
                            knowledgeBaseId,
                            currentUser.tenantId()
                    )
                    .orElseThrow(() -> new ApiException(
                            "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                            "Knowledge document not found",
                            HttpStatus.NOT_FOUND
                    ));
            if (document.getStatus() == KnowledgeDocumentStatus.DELETING) {
                return;
            }
            // Tombstoning is also the cancellation fence for queued/in-flight workers. A late
            // worker will fail its token/fence/status ownership check before committing more data.
            knowledgeChunkRepository.deleteAllByDocument_Id(document.getId());
            knowledgeChunkRepository.flush();
            document.setStatus(KnowledgeDocumentStatus.DELETING);
            document.setCompletedAt(null);
            document.setNextRetryAt(null);
            document.setContentSha256(null);
            document.setProcessingFence(Objects.requireNonNullElse(document.getProcessingFence(), 0L) + 1L);
            clearProcessingOwnership(document);
            knowledgeDocumentRepository.save(document);
            knowledgeDocumentCleanupService.enqueue(
                    document.getId(),
                    document.getStorageBucket(),
                    document.getStorageObjectKey()
            );
        });
    }

    /** Atomically reserves cumulative tenant chunk capacity before any embedding spend. */
    private void reserveSynchronousChunkQuota(Long documentId, Long tenantId, int chunkCount) {
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeDocumentRepository.lockTenantForImport(tenantId);
            KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                    .orElseThrow(() -> new ApiException(
                            "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                            "Knowledge document not found",
                            HttpStatus.NOT_FOUND
                    ));
            requireSynchronousProcessing(document);
            requireTenantChunkCapacity(tenantId, documentId, chunkCount);
            knowledgeChunkRepository.deleteAllByDocument_Id(documentId);
            knowledgeChunkRepository.flush();
            document.setChunkCount(chunkCount);
            knowledgeDocumentRepository.saveAndFlush(document);
        });
    }

    /** Uses tenant -> document lock order so concurrent workers cannot oversubscribe chunk quota. */
    private void reserveAsyncChunkQuota(
            Long documentId,
            int deliveryAttempt,
            AsyncWorkItem workItem,
            int chunkCount
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeDocumentRepository.lockTenantForImport(workItem.tenantId());
            KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                    .orElseThrow(() -> new KnowledgeIngestWorkSupersededException(documentId, deliveryAttempt));
            assertAsyncWorkOwnership(document, deliveryAttempt, workItem);
            requireTenantChunkCapacity(workItem.tenantId(), documentId, chunkCount);
            knowledgeChunkRepository.deleteAllByDocument_Id(documentId);
            knowledgeChunkRepository.flush();
            // chunk_count is both the eventual count and an atomic quota reservation while the
            // bounded batches are being embedded and inserted.
            document.setChunkCount(chunkCount);
            document.setProcessingLeaseUntil(leaseUntil());
            knowledgeDocumentRepository.saveAndFlush(document);
        });
    }

    /**
     * Holds at most embedding-batch-size vectors and rows at once. Text chunks remain bounded by
     * maxChunksPerDocument; every batch is fenced again before it can commit side effects.
     */
    private void embedAndPersistChunkBatches(
            Long documentId,
            Long tenantId,
            Long knowledgeBaseId,
            List<String> chunks,
            PreparedModelConfig embeddingModelConfig,
            AsyncBatchOwnership ownership
    ) {
        int batchSize = knowledgeProperties.getEmbeddingBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            if (ownership != null) {
                assertAndRenewAsyncWorkOwnership(documentId, ownership.deliveryAttempt(), ownership.workItem());
            }
            List<String> textBatch = List.copyOf(chunks.subList(start, end));
            List<float[]> vectors = embeddingGatewayService.embedTexts(embeddingModelConfig, textBatch);
            if (vectors == null || vectors.size() != textBatch.size()) {
                throw new ApiException(
                        "EMBEDDING_RESPONSE_SIZE_MISMATCH",
                        "Embedding response size does not match the submitted batch",
                        HttpStatus.BAD_GATEWAY
                );
            }
            List<KnowledgeChunkRow> rows = new ArrayList<>(textBatch.size());
            for (int offset = 0; offset < textBatch.size(); offset++) {
                rows.add(new KnowledgeChunkRow(start + offset, textBatch.get(offset), vectors.get(offset)));
            }
            transactionTemplate.executeWithoutResult(status -> persistChunkBatch(
                    documentId,
                    tenantId,
                    knowledgeBaseId,
                    rows,
                    ownership
            ));
        }
    }

    private void persistChunkBatch(
            Long documentId,
            Long tenantId,
            Long knowledgeBaseId,
            List<KnowledgeChunkRow> rows,
            AsyncBatchOwnership ownership
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                        "Knowledge document not found",
                        HttpStatus.NOT_FOUND
                ));
        if (ownership == null) {
            requireSynchronousProcessing(document);
        } else {
            assertAsyncWorkOwnership(document, ownership.deliveryAttempt(), ownership.workItem());
        }
        if (!Objects.equals(document.getTenant().getId(), tenantId)
                || !Objects.equals(document.getKnowledgeBase().getId(), knowledgeBaseId)) {
            throw new IllegalStateException("Knowledge chunk ownership changed while processing");
        }
        knowledgeChunkJdbcRepository.batchInsert(tenantId, knowledgeBaseId, documentId, rows);
        if (ownership != null) {
            document.setProcessingLeaseUntil(leaseUntil());
            knowledgeDocumentRepository.save(document);
        }
    }

    private KnowledgeDocument markAsyncDocumentIndexing(
            Long documentId,
            int deliveryAttempt,
            AsyncWorkItem workItem
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new KnowledgeIngestWorkSupersededException(documentId, deliveryAttempt));
        assertAsyncWorkOwnership(document, deliveryAttempt, workItem);
        document.setStatus(KnowledgeDocumentStatus.INDEXING);
        document.setProcessingLeaseUntil(leaseUntil());
        return knowledgeDocumentRepository.saveAndFlush(document);
    }

    private void requireSynchronousProcessing(KnowledgeDocument document) {
        if (document.getStatus() != KnowledgeDocumentStatus.PROCESSING) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_PROCESSING_SUPERSEDED",
                    "Knowledge document processing was superseded",
                    HttpStatus.CONFLICT
            );
        }
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
        boolean resumeIndexOnly = claimedFrom == KnowledgeDocumentStatus.INDEXING;
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
                document.getFileSize(),
                Objects.requireNonNullElse(document.getChunkCount(), 0),
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

    private int indexAsyncDocumentInPages(Long documentId, int deliveryAttempt, AsyncWorkItem workItem) {
        return indexDocumentInPages(documentId, () ->
                assertAndRenewAsyncWorkOwnership(documentId, deliveryAttempt, workItem));
    }

    private int indexDocumentInPages(Long documentId) {
        return indexDocumentInPages(documentId, () -> { });
    }

    private int indexDocumentInPages(Long documentId, Runnable betweenPages) {
        long afterChunkId = 0L;
        int indexed = 0;
        while (true) {
            List<KnowledgeChunkSearchDocument> page = knowledgeChunkJdbcRepository
                    .findSearchDocumentsByDocumentIdAfterChunkId(
                            documentId,
                            afterChunkId,
                            knowledgeSearchIndexer.bulkBatchSize()
                    );
            if (page.isEmpty()) {
                if (indexed == 0) {
                    throw new ApiException(
                            "KNOWLEDGE_DOCUMENT_CHUNKS_EMPTY",
                            "Knowledge document does not contain indexable chunks",
                            HttpStatus.CONFLICT
                    );
                }
                return indexed;
            }
            betweenPages.run();
            knowledgeSearchIndexer.indexSearchDocuments(page);
            indexed += page.size();
            afterChunkId = page.getLast().chunkId();
            if (page.size() < knowledgeSearchIndexer.bulkBatchSize()) {
                return indexed;
            }
        }
    }

    /**
     * 在索引完成后把文档标记为 READY。
     */
    private KnowledgeDocumentResponse markDocumentReady(Long documentId, int chunkCount) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
        requireSynchronousProcessing(document);
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
        } catch (RuntimeException cleanupEx) {
            log.warn(
                    "Direct cleanup of uploaded knowledge object failed; scheduling durable orphan cleanup, "
                            + "documentId={}, bucket={}, objectKey={}, reason={}",
                    documentId,
                    storedObject.bucket(),
                    storedObject.objectKey(),
                    cleanupEx.toString()
            );
            try {
                knowledgeDocumentCleanupService.enqueueOrphan(
                        storedObject.bucket(),
                        storedObject.objectKey()
                );
            } catch (RuntimeException enqueueEx) {
                log.error(
                        "Could not persist orphan knowledge object cleanup, bucket={}, objectKey={}",
                        storedObject.bucket(),
                        storedObject.objectKey(),
                        enqueueEx
                );
            }
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
        KnowledgeDocument document = knowledgeDocumentRepository
                .findByIdAndKnowledgeBase_IdAndTenant_Id(documentId, knowledgeBaseId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
        if (document.getStatus() == KnowledgeDocumentStatus.DELETING) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                    "Knowledge document not found",
                    HttpStatus.NOT_FOUND
            );
        }
        return document;
    }

    private void requireDuplicateNotDeleting(KnowledgeDocument duplicate) {
        if (duplicate.getStatus() == KnowledgeDocumentStatus.DELETING) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_DELETE_IN_PROGRESS",
                    "An identical document is still being deleted; retry the upload shortly",
                    HttpStatus.CONFLICT
            );
        }
    }

    /**
     * 上传前校验文件。
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateFileSize(long fileSize) {
        if (fileSize < 1) {
            throw new ApiException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
        if (fileSize > knowledgeProperties.getMaxFileSizeBytes()) {
            throw new ApiException(
                    "DOCUMENT_FILE_TOO_LARGE",
                    "Document exceeds the configured size limit",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
    }

    private void requireTenantImportCapacity(Long tenantId, long incomingFileSize) {
        long active = knowledgeDocumentRepository.countActiveImportsByTenant(tenantId);
        if (active >= knowledgeProperties.getMaxActiveImportsPerTenant()) {
            throw new ApiException(
                    "KNOWLEDGE_IMPORT_CAPACITY_EXCEEDED",
                    "Tenant has too many active document imports",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        long documentCount = knowledgeDocumentRepository.countQuotaDocumentsByTenant(tenantId);
        if (documentCount >= knowledgeProperties.getMaxDocumentsPerTenant()) {
            throw new ApiException(
                    "KNOWLEDGE_DOCUMENT_QUOTA_EXCEEDED",
                    "Tenant has reached its knowledge document quota; delete documents before uploading more",
                    HttpStatus.CONFLICT
            );
        }
        long usedBytes = knowledgeDocumentRepository.sumQuotaSourceBytesByTenant(tenantId);
        if (incomingFileSize > knowledgeProperties.getMaxSourceBytesPerTenant()
                || usedBytes > knowledgeProperties.getMaxSourceBytesPerTenant() - incomingFileSize) {
            throw new ApiException(
                    "KNOWLEDGE_SOURCE_STORAGE_QUOTA_EXCEEDED",
                    "Tenant has reached its knowledge source storage quota; FAILED documents count until deleted",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
    }

    private void requireTenantChunkCapacity(Long tenantId, Long documentId, int requestedChunks) {
        long otherReservedChunks = knowledgeDocumentRepository
                .sumQuotaChunksByTenantExcludingDocument(tenantId, documentId);
        long maximum = knowledgeProperties.getMaxChunksPerTenant();
        if (requestedChunks > maximum || otherReservedChunks > maximum - requestedChunks) {
            throw new ApiException(
                    "KNOWLEDGE_CHUNK_QUOTA_EXCEEDED",
                    "Tenant has reached its cumulative knowledge chunk quota; FAILED documents count until deleted",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
    }

    private String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
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
            Long fileSize,
            int chunkCount,
            String storageBucket,
            String storageObjectKey
    ) {
        private static AsyncWorkItem noop() {
            return new AsyncWorkItem(false, false, null, null, null, null, null, null, null, 0, null, null);
        }
    }

    private record EnqueueResult(KnowledgeDocument document, boolean duplicate) {
    }

    private record SyncCreateResult(KnowledgeDocument document, boolean duplicate) {
    }

    private record AsyncBatchOwnership(int deliveryAttempt, AsyncWorkItem workItem) {
    }
}
