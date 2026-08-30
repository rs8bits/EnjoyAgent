package com.enjoy.agent.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers abandoned knowledge ingestion leases. */
@Component
public class KnowledgeIngestLeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestLeaseReaper.class);
    private final KnowledgeDocumentApplicationService documentService;

    public KnowledgeIngestLeaseReaper(KnowledgeDocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    @Scheduled(
            fixedDelayString = "${enjoy.knowledge.ingest.lease-reaper-delay-ms:30000}",
            initialDelayString = "${enjoy.knowledge.ingest.lease-reaper-initial-delay-ms:30000}"
    )
    public void reapExpiredLeases() {
        try {
            int recovered = documentService.reapExpiredProcessingLeases();
            if (recovered > 0) {
                log.warn("Recovered {} expired knowledge ingestion leases", recovered);
            }
        } catch (RuntimeException ex) {
            log.warn("Knowledge ingestion lease reaper failed", ex);
        }
    }
}
