package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import java.time.Duration;

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

    @Test
    void resourceAndTenantLimits_shouldRejectNonPositiveValues() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxFileSizeBytes(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxPdfPages(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxExtractedTextChars(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxChunksPerDocument(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxActiveImportsPerTenant(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxDocumentsPerTenant(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxSourceBytesPerTenant(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxChunksPerTenant(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setExtractionTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxConcurrentExtractions(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setExtractionAdmissionTimeout(Duration.ZERO));
    }

    @Test
    void upstreamTimeoutAndResponseLimits_shouldRejectUnsafeValues() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setEmbeddingConnectTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setEmbeddingReadTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setEmbeddingRequestTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setEmbeddingMaxResponseBytes(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRerankConnectTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRerankReadTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRerankRequestTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRerankMaxResponseBytes(0));
    }
}
