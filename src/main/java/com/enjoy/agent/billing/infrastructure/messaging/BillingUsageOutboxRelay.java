package com.enjoy.agent.billing.infrastructure.messaging;

import com.enjoy.agent.billing.application.BillingUsageOutboxService;
import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingUsageOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(BillingUsageOutboxRelay.class);
    private final BillingUsageOutboxService outboxService;
    private final BillingUsageEventPublisher publisher;
    private final Counter success;
    private final Counter failure;

    public BillingUsageOutboxRelay(
            BillingUsageOutboxService outboxService,
            BillingUsageEventPublisher publisher,
            MeterRegistry meterRegistry
    ) {
        this.outboxService = outboxService;
        this.publisher = publisher;
        this.success = counter(meterRegistry, "success");
        this.failure = counter(meterRegistry, "failure");
    }

    @Scheduled(
            fixedDelayString = "${enjoy.billing.outbox-relay-delay-ms:500}",
            initialDelayString = "${enjoy.billing.outbox-relay-initial-delay-ms:500}"
    )
    public void relayDueEvents() {
        outboxService.reconcileMissingAndStaleOutboxes();
        var claimed = outboxService.claimDue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = claimed.stream()
                    .<Future<?>>map(outbox -> executor.submit(() -> publish(outbox)))
                    .toList();
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.error("Unexpected billing outbox relay task failure", ex);
                }
            }
        }
    }

    private void publish(BillingUsageOutbox outbox) {
        try {
            publisher.publish(outbox);
            outboxService.markPublished(outbox.getId());
            success.increment();
        } catch (RuntimeException ex) {
            failure.increment();
            log.warn("Publishing billing outbox failed, outboxId={}, eventId={}",
                    outbox.getId(), outbox.getUsageEventId(), ex);
            outboxService.markPublishFailed(outbox.getId(), ex);
        }
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("enjoy.outbox.publish")
                .tag("eventType", "billing_usage")
                .tag("outcome", outcome)
                .register(registry);
    }
}
