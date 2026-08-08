package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeIngestOutboxStatus;
import com.enjoy.agent.knowledge.infrastructure.messaging.KnowledgeIngestMessageCodec;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeIngestOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeIngestOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private final KnowledgeIngestOutboxRepository repository = mock(KnowledgeIngestOutboxRepository.class);
    private final KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
    private final KnowledgeIngestOutboxService service = new KnowledgeIngestOutboxService(
            repository,
            new KnowledgeIngestMessageCodec(new ObjectMapper().findAndRegisterModules()),
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void claimDue_shouldRecoverStaleClaimsAndIncrementPublishAttempt() {
        KnowledgeIngestOutbox outbox = outbox();
        outbox.setStatus(KnowledgeIngestOutboxStatus.PENDING);
        when(repository.findDueForUpdate(any(), any(), any(Integer.class))).thenReturn(List.of(outbox));

        List<KnowledgeIngestOutbox> claimed = service.claimDue();

        assertThat(claimed).containsExactly(outbox);
        assertThat(outbox.getStatus()).isEqualTo(KnowledgeIngestOutboxStatus.CLAIMED);
        assertThat(outbox.getClaimedAt()).isEqualTo(NOW);
        assertThat(outbox.getPublishAttempts()).isEqualTo(1);
        verify(repository).findDueForUpdate(
                NOW,
                NOW.minus(properties.getOutboxClaimTimeout()),
                properties.getOutboxBatchSize()
        );
    }

    @Test
    void publishFailure_shouldReturnClaimToPendingWithoutLosingIt() {
        KnowledgeIngestOutbox outbox = outbox();
        outbox.setStatus(KnowledgeIngestOutboxStatus.CLAIMED);
        outbox.setClaimedAt(NOW.minusSeconds(1));
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(outbox));

        service.markPublishFailed(9L, new RuntimeException("broker unavailable"));

        assertThat(outbox.getStatus()).isEqualTo(KnowledgeIngestOutboxStatus.PENDING);
        assertThat(outbox.getClaimedAt()).isNull();
        assertThat(outbox.getAvailableAt()).isEqualTo(NOW.plus(properties.getOutboxPublishRetryDelay()));
        assertThat(outbox.getLastError()).isEqualTo("broker unavailable");
    }

    private KnowledgeIngestOutbox outbox() {
        KnowledgeIngestOutbox outbox = new KnowledgeIngestOutbox();
        outbox.setId(9L);
        outbox.setDocumentId(42L);
        outbox.setDeliveryAttempt(1);
        outbox.setPayload("{}");
        outbox.setPublishAttempts(0);
        outbox.setAvailableAt(NOW);
        return outbox;
    }
}
