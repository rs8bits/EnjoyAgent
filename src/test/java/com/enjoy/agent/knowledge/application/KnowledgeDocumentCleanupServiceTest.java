package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enjoy.agent.knowledge.domain.entity.KnowledgeDocumentCleanupOutbox;
import com.enjoy.agent.knowledge.domain.enums.KnowledgeCleanupType;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentCleanupOutboxRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class KnowledgeDocumentCleanupServiceTest {

    @Test
    void enqueueShouldDelayPhysicalCleanupPastBoundedInFlightSearchRequest() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        KnowledgeDocumentCleanupOutboxRepository outboxRepository =
                mock(KnowledgeDocumentCleanupOutboxRepository.class);
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
        properties.setCleanupGracePeriod(Duration.ofSeconds(30));
        when(outboxRepository.findByDocumentId(42L)).thenReturn(Optional.empty());
        when(outboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeDocumentCleanupService service = new KnowledgeDocumentCleanupService(
                outboxRepository,
                mock(KnowledgeDocumentRepository.class),
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        KnowledgeDocumentCleanupOutbox outbox = service.enqueue(42L, "knowledge", "object-key");

        assertThat(outbox.getAvailableAt()).isEqualTo(now.plusSeconds(30));
        assertThat(outbox.getDocumentId()).isEqualTo(42L);
        assertThat(outbox.getCleanupType()).isEqualTo(KnowledgeCleanupType.DOCUMENT_DELETE);
    }

    @Test
    void orphanObjectShouldBeDurablyQueuedWithoutPointingAtRealDocument() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        KnowledgeDocumentCleanupOutboxRepository outboxRepository =
                mock(KnowledgeDocumentCleanupOutboxRepository.class);
        when(outboxRepository.findByStorageBucketAndStorageObjectKey("knowledge", "orphan-key"))
                .thenReturn(Optional.empty());
        when(outboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeDocumentCleanupService service = new KnowledgeDocumentCleanupService(
                outboxRepository,
                mock(KnowledgeDocumentRepository.class),
                new KnowledgeIngestProperties(),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        KnowledgeDocumentCleanupOutbox outbox = service.enqueueOrphan("knowledge", "orphan-key");

        assertThat(outbox.getCleanupType()).isEqualTo(KnowledgeCleanupType.ORPHAN_OBJECT);
        assertThat(outbox.getDocumentId()).isNull();
        assertThat(outbox.getAvailableAt()).isEqualTo(now);
    }

    @Test
    void orphanCompensationShouldUseAnIndependentTransactionAfterUploadRollback() throws Exception {
        Transactional transactional = KnowledgeDocumentCleanupService.class
                .getMethod("enqueueOrphan", String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
