package com.enjoy.agent.knowledge.infrastructure.messaging;

import com.enjoy.agent.knowledge.application.KnowledgeIngestOutboxService;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性发布到期 Outbox。确认后才推进为 PUBLISHED。
 */
@Component
public class KnowledgeIngestOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestOutboxRelay.class);

    private final KnowledgeIngestOutboxService outboxService;
    private final KnowledgeIngestPublisher publisher;
    private final Counter publishSuccess;
    private final Counter publishFailure;

    public KnowledgeIngestOutboxRelay(
            KnowledgeIngestOutboxService outboxService,
            KnowledgeIngestPublisher publisher,
            MeterRegistry meterRegistry
    ) {
        this.outboxService = outboxService;
        this.publisher = publisher;
        this.publishSuccess = Counter.builder("enjoy.outbox.publish")
                .tag("eventType", "knowledge_document_ingest")
                .tag("outcome", "success")
                .register(meterRegistry);
        this.publishFailure = Counter.builder("enjoy.outbox.publish")
                .tag("eventType", "knowledge_document_ingest")
                .tag("outcome", "failure")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${enjoy.knowledge.ingest.outbox-relay-delay-ms:500}",
            initialDelayString = "${enjoy.knowledge.ingest.outbox-relay-initial-delay-ms:500}"
    )
    public void relayDueMessages() {
        for (int index = 0; index < outboxService.relayBatchSize(); index++) {
            KnowledgeIngestOutbox outbox = outboxService.claimNextDue().orElse(null);
            if (outbox == null) {
                return;
            }
            try {
                publisher.publish(outbox);
                outboxService.markPublished(outbox.getId());
                publishSuccess.increment();
            } catch (RuntimeException ex) {
                publishFailure.increment();
                log.warn(
                        "发布知识文档导入 Outbox 失败，outboxId={}, documentId={}, attempt={}",
                        outbox.getId(),
                        outbox.getDocumentId(),
                        outbox.getPublishAttempts(),
                        ex
                );
                outboxService.markPublishFailed(outbox.getId(), ex);
            }
        }
    }
}
