package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

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
}
