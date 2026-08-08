package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.knowledge.api.response.KnowledgeDocumentResponse;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocument;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeDocumentStatus;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkJdbcRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeDocumentApplicationServiceAsyncTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";

    private final KnowledgeBaseApplicationService knowledgeBaseService = mock(KnowledgeBaseApplicationService.class);
    private final KnowledgeDocumentRepository documentRepository = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeChunkRepository chunkRepository = mock(KnowledgeChunkRepository.class);
    private final KnowledgeChunkJdbcRepository chunkJdbcRepository = mock(KnowledgeChunkJdbcRepository.class);
    private final KnowledgeSearchIndexer searchIndexer = mock(KnowledgeSearchIndexer.class);
    private final MinioStorageService minioStorageService = mock(MinioStorageService.class);
    private final DocumentTextExtractor textExtractor = mock(DocumentTextExtractor.class);
    private final DocumentChunker chunker = mock(DocumentChunker.class);
    private final EmbeddingGatewayService embeddingGatewayService = mock(EmbeddingGatewayService.class);
    private final KnowledgeIngestOutboxService outboxService = mock(KnowledgeIngestOutboxService.class);
    private final KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private KnowledgeDocumentApplicationService service;

    @BeforeEach
    void setUp() {
        authenticate();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new KnowledgeDocumentApplicationService(
                knowledgeBaseService,
                documentRepository,
                chunkRepository,
                chunkJdbcRepository,
                searchIndexer,
                minioStorageService,
                textExtractor,
                chunker,
                embeddingGatewayService,
                outboxService,
                properties,
                new TransactionTemplate(transactionManager),
                new SimpleMeterRegistry(),
                clock
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void asyncUpload_shouldOnlyStoreObjectAndAtomicallyCreateQueuedOutbox() {
        KnowledgeBase knowledgeBase = knowledgeBase();
        when(knowledgeBaseService.requireRunnableKnowledgeBase(11L)).thenReturn(knowledgeBase);
        when(minioStorageService.uploadKnowledgeDocument(eq(11L), eq("guide.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(new StoredObjectInfo("knowledge", "knowledge-base/11/guide.txt"));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(42L);
            return document;
        });

        KnowledgeDocumentResponse response = service.uploadDocumentFromBytes(
                11L,
                "guide.txt",
                "text/plain",
                "hello".getBytes()
        );

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.processingAttempts()).isZero();
        verify(outboxService).enqueue(42L, 1, NOW);
        verify(textExtractor, never()).extractText(any(), any(), any());
        verify(embeddingGatewayService, never()).embedTexts(any(), any());
    }

    @Test
    void transientFailure_shouldPersistRetryAndNextOutbox() {
        KnowledgeDocument document = processingDocument(1);
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        properties.setRetryDelays(List.of(Duration.ofSeconds(5)));

        KnowledgeIngestFailureDisposition disposition = service.handleAsyncProcessingFailure(
                42L,
                1,
                TOKEN,
                new RuntimeException("embedding unavailable")
        );

        assertThat(disposition).isEqualTo(KnowledgeIngestFailureDisposition.RETRY_SCHEDULED);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.RETRYING);
        assertThat(document.getNextRetryAt()).isEqualTo(NOW.plusSeconds(5));
        verify(outboxService).enqueue(42L, 2, NOW.plusSeconds(5));
    }

    @Test
    void permanentFailure_shouldMarkFailedWithoutCreatingRetry() {
        KnowledgeDocument document = processingDocument(1);
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeIngestFailureDisposition disposition = service.handleAsyncProcessingFailure(
                42L,
                1,
                TOKEN,
                new ApiException("DOCUMENT_TEXT_EMPTY", "Document text is empty", HttpStatus.BAD_REQUEST)
        );

        assertThat(disposition).isEqualTo(KnowledgeIngestFailureDisposition.PARK);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        assertThat(document.getCompletedAt()).isEqualTo(NOW);
        verify(outboxService, never()).enqueue(any(), anyInt(), any());
    }

    @Test
    void transientFailure_shouldParkAfterFiniteAttempts() {
        KnowledgeDocument document = processingDocument(3);
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        properties.setMaxAttempts(3);

        KnowledgeIngestFailureDisposition disposition = service.handleAsyncProcessingFailure(
                42L,
                3,
                TOKEN,
                new RuntimeException("embedding still unavailable")
        );

        assertThat(disposition).isEqualTo(KnowledgeIngestFailureDisposition.PARK);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        verify(outboxService, never()).enqueue(any(), anyInt(), any());
    }

    @Test
    void duplicateDeliveryForReadyDocument_shouldBeBusinessNoop() {
        KnowledgeDocument document = processingDocument(1);
        document.setStatus(KnowledgeDocumentStatus.READY);
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));

        service.processAsyncDocument(42L, 1, true, TOKEN);

        verify(minioStorageService, never()).downloadObject(any(), any());
        verify(textExtractor, never()).extractText(any(), any(), any());
        verify(embeddingGatewayService, never()).embedTexts(any(), any());
    }

    @Test
    void redeliveryFromIndexing_shouldResumeIndexWithoutRepeatingEmbedding() {
        KnowledgeDocument document = processingDocument(1);
        document.setStatus(KnowledgeDocumentStatus.INDEXING);
        document.setTenant(knowledgeBase().getTenant());
        document.setKnowledgeBase(knowledgeBase());
        document.setFileName("guide.txt");
        document.setStorageBucket("knowledge");
        document.setStorageObjectKey("knowledge-base/11/guide.txt");
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeChunkSearchDocument searchDocument = new KnowledgeChunkSearchDocument(
                100L,
                7L,
                11L,
                42L,
                "guide.txt",
                0,
                "hello",
                new float[]{0.1f},
                NOW,
                NOW
        );
        when(chunkJdbcRepository.findSearchDocumentsByDocumentId(42L)).thenReturn(List.of(searchDocument));

        service.processAsyncDocument(42L, 1, true, TOKEN);

        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        verify(searchIndexer, never()).deleteDocumentChunks(42L);
        verify(searchIndexer).indexSearchDocuments(List.of(searchDocument));
        verify(minioStorageService, never()).downloadObject(any(), any());
        verify(embeddingGatewayService, never()).embedTexts(any(), any());
    }

    @Test
    void regularIndexFailure_shouldKeepCommittedChunksAndRetryIndexOnly() {
        KnowledgeDocument document = processingDocument(1);
        document.setStatus(KnowledgeDocumentStatus.INDEXING);
        document.setChunkCount(1);
        document.setTenant(knowledgeBase().getTenant());
        document.setKnowledgeBase(knowledgeBase());
        document.setFileName("guide.txt");
        document.setStorageBucket("knowledge");
        document.setStorageObjectKey("knowledge-base/11/guide.txt");
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeChunkSearchDocument searchDocument = new KnowledgeChunkSearchDocument(
                100L, 7L, 11L, 42L, "guide.txt", 0, "hello", new float[]{0.1f}, NOW, NOW
        );
        when(chunkJdbcRepository.findSearchDocumentsByDocumentId(42L)).thenReturn(List.of(searchDocument));

        KnowledgeIngestFailureDisposition disposition = service.handleAsyncProcessingFailure(
                42L,
                1,
                TOKEN,
                new ApiException("KNOWLEDGE_SEARCH_BULK_UPSERT_FAILED", "Search unavailable", HttpStatus.BAD_GATEWAY)
        );

        assertThat(disposition).isEqualTo(KnowledgeIngestFailureDisposition.RETRY_SCHEDULED);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.INDEXING);
        assertThat(document.getProcessingToken()).isNull();
        verify(outboxService).enqueue(42L, 2, NOW.plusSeconds(5));

        service.processAsyncDocument(
                42L,
                2,
                false,
                "00000000-0000-0000-0000-000000000002"
        );

        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(document.getProcessingAttempts()).isEqualTo(2);
        verify(searchIndexer).indexSearchDocuments(List.of(searchDocument));
        verify(minioStorageService, never()).downloadObject(any(), any());
        verify(embeddingGatewayService, never()).embedTexts(any(), any());
        verify(searchIndexer, never()).deleteDocumentChunks(any());
    }

    @Test
    void supersededToken_shouldStopOldWorkerBeforeFurtherSideEffects() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(42L);
        document.setStatus(KnowledgeDocumentStatus.QUEUED);
        document.setProcessingAttempts(0);
        document.setProcessingFence(0L);
        document.setTenant(knowledgeBase().getTenant());
        document.setKnowledgeBase(knowledgeBase());
        document.setFileName("guide.txt");
        document.setContentType("text/plain");
        document.setStorageBucket("knowledge");
        document.setStorageObjectKey("knowledge-base/11/guide.txt");
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(minioStorageService.downloadObject("knowledge", "knowledge-base/11/guide.txt"))
                .thenAnswer(invocation -> {
                    document.setProcessingToken("00000000-0000-0000-0000-000000000099");
                    document.setProcessingFence(99L);
                    return "hello".getBytes();
                });

        assertThatThrownBy(() -> service.processAsyncDocument(42L, 1, false, TOKEN))
                .isInstanceOf(KnowledgeIngestWorkSupersededException.class);

        verify(textExtractor, never()).extractText(any(), any(), any());
        verify(embeddingGatewayService, never()).embedTexts(any(), any());
        verify(searchIndexer, never()).indexSearchDocuments(any());
        verify(searchIndexer, never()).deleteDocumentChunks(any());
    }

    @Test
    void outerTransactionRollback_shouldCompensateUploadedMinioObjectExactlyOnce() {
        KnowledgeBase knowledgeBase = knowledgeBase();
        when(knowledgeBaseService.requireRunnableKnowledgeBase(11L)).thenReturn(knowledgeBase);
        when(minioStorageService.uploadKnowledgeDocument(eq(11L), eq("guide.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(new StoredObjectInfo("knowledge", "knowledge-base/11/guide.txt"));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(42L);
            return document;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadDocumentFromBytes(11L, "guide.txt", "text/plain", "hello".getBytes());
            verify(minioStorageService, never()).removeObject(any(), any());

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(minioStorageService, times(1))
                    .removeObject("knowledge", "knowledge-base/11/guide.txt");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void synchronousFailure_shouldRetainSourceSoManualRetryCanRun() {
        properties.setAsyncEnabled(false);
        KnowledgeBase knowledgeBase = knowledgeBase();
        KnowledgeDocument failedDocument = new KnowledgeDocument();
        failedDocument.setId(42L);
        failedDocument.setTenant(knowledgeBase.getTenant());
        failedDocument.setKnowledgeBase(knowledgeBase);
        failedDocument.setFileName("guide.txt");
        failedDocument.setContentType("text/plain");
        failedDocument.setFileSize(5L);
        failedDocument.setStorageBucket("knowledge");
        failedDocument.setStorageObjectKey("knowledge-base/11/guide.txt");
        failedDocument.setStatus(KnowledgeDocumentStatus.PROCESSING);
        failedDocument.setChunkCount(0);
        failedDocument.setProcessingAttempts(0);
        when(knowledgeBaseService.requireRunnableKnowledgeBase(11L)).thenReturn(knowledgeBase);
        when(minioStorageService.uploadKnowledgeDocument(eq(11L), eq("guide.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(new StoredObjectInfo("knowledge", "knowledge-base/11/guide.txt"));
        when(documentRepository.saveAndFlush(any(KnowledgeDocument.class))).thenReturn(failedDocument);
        when(documentRepository.findById(42L)).thenReturn(Optional.of(failedDocument));
        when(textExtractor.extractText(eq("guide.txt"), eq("text/plain"), any(byte[].class)))
                .thenThrow(new ApiException("DOCUMENT_TEXT_EXTRACT_FAILED", "Extraction failed", HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.uploadDocumentFromBytes(
                11L, "guide.txt", "text/plain", "hello".getBytes()
        )).isInstanceOf(ApiException.class);

        assertThat(failedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        verify(minioStorageService, never()).removeObject(any(), any());

        when(documentRepository.findByIdAndKnowledgeBase_IdAndTenant_Id(42L, 11L, 7L))
                .thenReturn(Optional.of(failedDocument));
        when(documentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(failedDocument));
        service.retryFailedDocument(11L, 42L);

        assertThat(failedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.RETRYING);
        verify(outboxService).enqueue(42L, 1, NOW);
    }

    private KnowledgeBase knowledgeBase() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(11L);
        knowledgeBase.setTenant(tenant);
        return knowledgeBase;
    }

    private KnowledgeDocument processingDocument(int attempts) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(42L);
        document.setStatus(KnowledgeDocumentStatus.PROCESSING);
        document.setProcessingAttempts(attempts);
        document.setProcessingToken(TOKEN);
        document.setProcessingFence(1L);
        return document;
    }

    private void authenticate() {
        AuthenticatedUser user = new AuthenticatedUser(
                3L,
                "user@example.com",
                "User",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities())
        );
    }
}
