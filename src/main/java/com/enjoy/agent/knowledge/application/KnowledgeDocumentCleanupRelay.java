package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocumentCleanupOutbox;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupType;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeDocumentCleanupRelay {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentCleanupRelay.class);
    private final KnowledgeDocumentCleanupService cleanupService;
    private final KnowledgeSearchIndexer searchIndexer;
    private final MinioStorageService storageService;

    public KnowledgeDocumentCleanupRelay(
            KnowledgeDocumentCleanupService cleanupService,
            KnowledgeSearchIndexer searchIndexer,
            MinioStorageService storageService
    ) {
        this.cleanupService = cleanupService;
        this.searchIndexer = searchIndexer;
        this.storageService = storageService;
    }

    @Scheduled(
            fixedDelayString = "${enjoy.knowledge.ingest.cleanup-relay-delay-ms:1000}",
            initialDelayString = "${enjoy.knowledge.ingest.cleanup-relay-initial-delay-ms:1000}"
    )
    public void cleanupDueDocuments() {
        var claimed = cleanupService.claimDue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = claimed.stream()
                    .<Future<?>>map(outbox -> executor.submit(() -> cleanup(outbox)))
                    .toList();
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.error("Unexpected knowledge cleanup relay task failure", ex);
                }
            }
        }
    }

    private void cleanup(KnowledgeDocumentCleanupOutbox outbox) {
        try {
            if (outbox.getCleanupType() == KnowledgeCleanupType.DOCUMENT_DELETE
                    && outbox.getDocumentId() != null) {
                searchIndexer.deleteDocumentChunks(outbox.getDocumentId());
            }
            storageService.removeObject(outbox.getStorageBucket(), outbox.getStorageObjectKey());
            cleanupService.markCompleted(outbox.getId());
        } catch (RuntimeException ex) {
            log.warn("Knowledge object cleanup failed, outboxId={}, type={}, documentId={}",
                    outbox.getId(), outbox.getCleanupType(), outbox.getDocumentId(), ex);
            cleanupService.markFailed(outbox.getId(), ex);
        }
    }
}
