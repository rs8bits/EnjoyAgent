package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.billing.application.BillingUsageOutboxService;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageOutboxRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentCleanupOutboxRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeDocumentRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeIngestOutboxRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

class KnowledgeMultiInstanceClaimContractTest {

    @Test
    void leaseCleanupAndOutboxClaimsShouldAllSkipRowsLockedByAnotherInstance() throws Exception {
        assertSkipLocked(KnowledgeDocumentRepository.class.getMethod(
                "findExpiredProcessingLeasesForUpdate", Instant.class, int.class));
        assertSkipLocked(KnowledgeDocumentCleanupOutboxRepository.class.getMethod(
                "findDueForUpdate", Instant.class, Instant.class, int.class));
        assertSkipLocked(KnowledgeIngestOutboxRepository.class.getMethod(
                "findDueForUpdate", Instant.class, Instant.class, int.class));
        assertSkipLocked(BillingUsageOutboxRepository.class.getMethod(
                "findDueForUpdate", Instant.class, Instant.class, int.class));
        assertSkipLocked(BillingUsageEventRepository.class.getMethod(
                "findPendingIdsMissingOutboxForUpdate", int.class));
    }

    @Test
    void claimMethodsShouldCommitTheirDatabaseFenceBeforeExternalWorkBegins() throws Exception {
        assertThat(KnowledgeDocumentCleanupService.class.getMethod("claimDue")
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(KnowledgeIngestOutboxService.class.getMethod("claimNextDue")
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(BillingUsageOutboxService.class.getMethod("claimDue")
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(KnowledgeDocumentCleanupRelay.class.getMethod("cleanupDueDocuments")
                .getAnnotation(Transactional.class)).isNull();
    }

    private void assertSkipLocked(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("@Query on %s", method).isNotNull();
        assertThat(query.value())
                .containsIgnoringCase("FOR UPDATE")
                .containsIgnoringCase("SKIP LOCKED")
                .containsIgnoringCase("LIMIT");
    }
}
