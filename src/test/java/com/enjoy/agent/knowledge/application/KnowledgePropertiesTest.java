package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class KnowledgePropertiesTest {

    @Test
    void embeddingBatchSize_shouldDefaultToTunedValue() {
        assertThat(new KnowledgeProperties().getEmbeddingBatchSize()).isEqualTo(20);
    }

    @Test
    void embeddingBatchSize_shouldAcceptPositiveValues() {
        KnowledgeProperties properties = new KnowledgeProperties();

        properties.setEmbeddingBatchSize(32);

        assertThat(properties.getEmbeddingBatchSize()).isEqualTo(32);
    }

    @Test
    void embeddingBatchSize_shouldRejectNonPositiveValues() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setEmbeddingBatchSize(0))
                .withMessage("Embedding batch size must be positive");
    }
}
