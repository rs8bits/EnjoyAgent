package com.enjoy.agent.billing.application;

import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
import com.enjoy.agent.billing.domain.enums.BillingOutboxStatus;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingUsageOutboxService {

    private static final int ERROR_MAX_LENGTH = 1000;
    private final BillingUsageOutboxRepository outboxRepository;
    private final BillingUsageEventRepository eventRepository;
    private final BillingProperties properties;
    private final Clock clock;

    public BillingUsageOutboxService(
            BillingUsageOutboxRepository outboxRepository,
            BillingUsageEventRepository eventRepository,
            BillingProperties properties,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public BillingUsageOutbox enqueue(Long eventId, Instant availableAt) {
        Optional<BillingUsageOutbox> existing = outboxRepository.findByUsageEventId(eventId);
        if (existing.isPresent()) {
            return existing.get();
        }
        BillingUsageOutbox outbox = new BillingUsageOutbox();
        outbox.setUsageEventId(eventId);
        outbox.setStatus(BillingOutboxStatus.PENDING);
        outbox.setPublishAttempts(0);
        outbox.setAvailableAt(availableAt);
        return outboxRepository.save(outbox);
    }

    @Transactional
    public List<BillingUsageOutbox> claimDue() {
        Instant now = Instant.now(clock);
        List<BillingUsageOutbox> due = outboxRepository.findDueForUpdate(
                now,
                now.minus(properties.getRabbit().getOutboxClaimTimeout()),
                properties.getRabbit().getOutboxBatchSize()
        );
        for (BillingUsageOutbox outbox : due) {
            outbox.setStatus(BillingOutboxStatus.CLAIMED);
            outbox.setClaimedAt(now);
            outbox.setPublishAttempts(Math.max(0, nullSafe(outbox.getPublishAttempts())) + 1);
            outbox.setLastError(null);
        }
        return List.copyOf(due);
    }

    @Transactional
    public void markPublished(Long outboxId) {
        outboxRepository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != BillingOutboxStatus.CLAIMED) {
                return;
            }
            outbox.setStatus(BillingOutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now(clock));
            outbox.setClaimedAt(null);
            outbox.setLastError(null);
        });
    }

    @Transactional
    public void markPublishFailed(Long outboxId, RuntimeException failure) {
        outboxRepository.findByIdForUpdate(outboxId).ifPresent(outbox -> {
            if (outbox.getStatus() != BillingOutboxStatus.CLAIMED) {
                return;
            }
            outbox.setStatus(BillingOutboxStatus.PENDING);
            outbox.setClaimedAt(null);
            outbox.setAvailableAt(Instant.now(clock).plus(properties.getRabbit().getOutboxPublishRetryDelay()));
            outbox.setLastError(truncate(failure.getMessage()));
        });
    }

    @Transactional
    public void scheduleProcessingRetry(Long eventId, Instant availableAt, String reason) {
        BillingUsageOutbox outbox = outboxRepository.findByUsageEventIdForUpdate(eventId).orElse(null);
        if (outbox == null) {
            enqueue(eventId, availableAt);
            return;
        }
            outbox.setStatus(BillingOutboxStatus.PENDING);
            outbox.setAvailableAt(availableAt);
            outbox.setClaimedAt(null);
            outbox.setPublishedAt(null);
            outbox.setLastError(truncate(reason));
    }

    @Transactional
    public int reconcileMissingAndStaleOutboxes() {
        int limit = properties.getRabbit().getOutboxBatchSize();
        Instant now = Instant.now(clock);
        List<Long> missing = eventRepository.findPendingIdsMissingOutboxForUpdate(limit);
        missing.forEach(eventId -> enqueue(eventId, now));
        int reset = outboxRepository.resetPublishedForUnprocessedEvents(
                now,
                now.minus(properties.getRabbit().getReconciliationDelay())
        );
        return missing.size() + reset;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
