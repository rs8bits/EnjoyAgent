package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class KnowledgeIngestPropertiesTest {

    @Test
    void workerPressure_shouldDefaultToVerifiedBalancedCandidate() {
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();

        assertThat(properties.getWorkerConcurrency()).isEqualTo(4);
        assertThat(properties.getPrefetch()).isEqualTo(2);
    }

    @Test
    void manualRedrive_shouldStartANewFiniteRetryCycle() {
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
        properties.setMaxAttempts(3);

        assertThat(properties.isLastAttemptInCycle(1)).isFalse();
        assertThat(properties.isLastAttemptInCycle(2)).isFalse();
        assertThat(properties.isLastAttemptInCycle(3)).isTrue();
        assertThat(properties.attemptInCycle(4)).isEqualTo(1);
        assertThat(properties.isLastAttemptInCycle(6)).isTrue();
    }

    @Test
    void cleanupGracePeriod_shouldAllowZeroButRejectNegativeValues() {
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();

        properties.setCleanupGracePeriod(Duration.ZERO);
        assertThat(properties.getCleanupGracePeriod()).isZero();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setCleanupGracePeriod(Duration.ofMillis(-1)));
    }

    @Test
    void workerOutboxLeaseAndCleanupBounds_shouldRejectUnsafeValues() {
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWorkerConcurrency(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setPrefetch(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxAttempts(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRetryDelays(List.of(Duration.ofSeconds(-1))));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setOutboxBatchSize(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setOutboxClaimTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setPublisherConfirmTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setOutboxPublishRetryDelay(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setProcessingLeaseDuration(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setLeaseReaperBatchSize(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCleanupBatchSize(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCleanupClaimTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCleanupRetryDelay(Duration.ZERO));
    }
}
