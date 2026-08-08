package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeIngestOutboxStatus;
import com.enjoy.agent.knowledge.infrastructure.messaging.KnowledgeIngestMessage;
import com.enjoy.agent.knowledge.infrastructure.messaging.KnowledgeIngestMessageCodec;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeIngestOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创建、抢占和推进知识文档导入 Outbox。
 */
@Service
public class KnowledgeIngestOutboxService {

    private static final int ERROR_MAX_LENGTH = 1000;

    private final KnowledgeIngestOutboxRepository repository;
    private final KnowledgeIngestMessageCodec messageCodec;
    private final KnowledgeIngestProperties properties;
    private final Clock clock;

    public KnowledgeIngestOutboxService(
            KnowledgeIngestOutboxRepository repository,
            KnowledgeIngestMessageCodec messageCodec,
            KnowledgeIngestProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.messageCodec = messageCodec;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 必须和文档状态变更处于同一数据库事务中。
     */
    @Transactional
    public KnowledgeIngestOutbox enqueue(Long documentId, int deliveryAttempt, Instant availableAt) {
        KnowledgeIngestMessage message = new KnowledgeIngestMessage(
                KnowledgeIngestMessage.CURRENT_VERSION,
                documentId,
                deliveryAttempt,
                Instant.now(clock)
        );
        KnowledgeIngestOutbox outbox = new KnowledgeIngestOutbox();
        outbox.setDocumentId(documentId);
        outbox.setDeliveryAttempt(deliveryAttempt);
        outbox.setPayload(messageCodec.encode(message));
        outbox.setStatus(KnowledgeIngestOutboxStatus.PENDING);
        outbox.setPublishAttempts(0);
        outbox.setAvailableAt(availableAt);
        return repository.save(outbox);
    }

    @Transactional
    public List<KnowledgeIngestOutbox> claimDue() {
        return claimDue(properties.getOutboxBatchSize());
    }

    /**
     * Relay 每次只抢占即将发布的一条，避免整批消息在等待 confirm 时超过 claim timeout。
     */
    @Transactional
    public Optional<KnowledgeIngestOutbox> claimNextDue() {
        return claimDue(1).stream().findFirst();
    }

    public int relayBatchSize() {
        return properties.getOutboxBatchSize();
    }

    private List<KnowledgeIngestOutbox> claimDue(int limit) {
        Instant now = Instant.now(clock);
        List<KnowledgeIngestOutbox> claimed = repository.findDueForUpdate(
                now,
                now.minus(properties.getOutboxClaimTimeout()),
                limit
        );
        claimed.forEach(outbox -> {
            outbox.setStatus(KnowledgeIngestOutboxStatus.CLAIMED);
            outbox.setClaimedAt(now);
            outbox.setPublishAttempts(outbox.getPublishAttempts() + 1);
            outbox.setLastError(null);
        });
        return claimed;
    }

    @Transactional
    public void markPublished(Long outboxId) {
        repository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != KnowledgeIngestOutboxStatus.CLAIMED) {
                return;
            }
            outbox.setStatus(KnowledgeIngestOutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now(clock));
            outbox.setClaimedAt(null);
            outbox.setLastError(null);
        });
    }

    @Transactional
    public void markPublishFailed(Long outboxId, RuntimeException failure) {
        repository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != KnowledgeIngestOutboxStatus.CLAIMED) {
                return;
            }
            outbox.setStatus(KnowledgeIngestOutboxStatus.PENDING);
            outbox.setClaimedAt(null);
            outbox.setAvailableAt(Instant.now(clock).plus(properties.getOutboxPublishRetryDelay()));
            outbox.setLastError(truncate(failure.getMessage()));
        });
    }

    private String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }
}
