package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
import com.enjoy.agent.billing.domain.enums.BillingOutboxStatus;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BillingUsageOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private BillingUsageOutboxRepository outboxRepository;
    private BillingUsageEventRepository eventRepository;
    private BillingProperties properties;
    private BillingUsageOutboxService service;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(BillingUsageOutboxRepository.class);
        eventRepository = mock(BillingUsageEventRepository.class);
        properties = new BillingProperties();
        service = new BillingUsageOutboxService(
                outboxRepository,
                eventRepository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void enqueueShouldBeIdempotentPerUsageEvent() {
        BillingUsageOutbox existing = outbox(11L, 41L, BillingOutboxStatus.PUBLISHED);
        when(outboxRepository.findByUsageEventId(41L)).thenReturn(Optional.of(existing));

        assertThat(service.enqueue(41L, NOW)).isSameAs(existing);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void claimShouldFenceDueRowsBeforePublishingOutsideTransaction() {
        BillingUsageOutbox due = outbox(11L, 41L, BillingOutboxStatus.PENDING);
        due.setPublishAttempts(2);
        when(outboxRepository.findDueForUpdate(
                NOW,
                NOW.minus(properties.getRabbit().getOutboxClaimTimeout()),
                properties.getRabbit().getOutboxBatchSize()
        )).thenReturn(List.of(due));

        assertThat(service.claimDue()).containsExactly(due);
        assertThat(due.getStatus()).isEqualTo(BillingOutboxStatus.CLAIMED);
        assertThat(due.getClaimedAt()).isEqualTo(NOW);
        assertThat(due.getPublishAttempts()).isEqualTo(3);
    }

    @Test
    void reconciliationShouldRecreateMissingRowsAndRedriveStalePublishedRows() {
        when(eventRepository.findPendingIdsMissingOutboxForUpdate(
                properties.getRabbit().getOutboxBatchSize()
        )).thenReturn(List.of(41L, 42L));
        when(outboxRepository.findByUsageEventId(41L)).thenReturn(Optional.empty());
        when(outboxRepository.findByUsageEventId(42L)).thenReturn(Optional.empty());
        when(outboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.resetPublishedForUnprocessedEvents(
                NOW,
                NOW.minus(properties.getRabbit().getReconciliationDelay())
        )).thenReturn(3);

        assertThat(service.reconcileMissingAndStaleOutboxes()).isEqualTo(5);

        verify(outboxRepository).resetPublishedForUnprocessedEvents(
                NOW,
                NOW.minus(properties.getRabbit().getReconciliationDelay())
        );
    }

    private BillingUsageOutbox outbox(Long id, Long eventId, BillingOutboxStatus status) {
        BillingUsageOutbox outbox = new BillingUsageOutbox();
        outbox.setId(id);
        outbox.setUsageEventId(eventId);
        outbox.setStatus(status);
        return outbox;
    }
}
