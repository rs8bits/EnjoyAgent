package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(new KnowledgeProperties());

    @Test
    void pdfDeclarationShouldRequirePdfMagicBytes() {
        assertThatThrownBy(() -> extractor.extractText(
                "renamed.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DOCUMENT_TYPE_MISMATCH");
    }

    @Test
    void textDeclarationShouldRejectBinaryNulBytes() {
        assertThatThrownBy(() -> extractor.extractText(
                "payload.txt",
                "text/plain",
                new byte[]{'a', 0, 'b'}
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DOCUMENT_BINARY_CONTENT_REJECTED");
    }

    @Test
    void slowParserInputShouldBeCancelledAtWallClockDeadline() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setExtractionTimeout(Duration.ofMillis(25));
        DocumentTextExtractor timedExtractor = new DocumentTextExtractor(properties);

        assertThatThrownBy(() -> timedExtractor.extractText(
                "slow.txt",
                "text/plain",
                new InterruptibleSlowInputStream()
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DOCUMENT_EXTRACTION_TIMEOUT");
    }

    @Test
    void parserIgnoringInterruptShouldRetainItsSlotAndBoundCpuAmplification() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setExtractionTimeout(Duration.ofMillis(20));
        properties.setExtractionAdmissionTimeout(Duration.ofMillis(10));
        properties.setMaxConcurrentExtractions(1);
        DocumentTextExtractor boundedExtractor = new DocumentTextExtractor(properties);

        assertThatThrownBy(() -> boundedExtractor.extractText(
                "slow.txt",
                "text/plain",
                new TemporarilyUninterruptibleInputStream(250)
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DOCUMENT_EXTRACTION_TIMEOUT");

        assertThatThrownBy(() -> boundedExtractor.extractText(
                "second.txt",
                "text/plain",
                "ok".getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DOCUMENT_EXTRACTION_CAPACITY_EXCEEDED");
    }

    private static final class InterruptibleSlowInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(5_000L);
                return -1;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }
    }

    private static final class TemporarilyUninterruptibleInputStream extends InputStream {

        private final long finishAtNanos;

        private TemporarilyUninterruptibleInputStream(long durationMillis) {
            this.finishAtNanos = System.nanoTime() + Duration.ofMillis(durationMillis).toNanos();
        }

        @Override
        public int read() {
            while (System.nanoTime() < finishAtNanos) {
                // Simulates a third-party parser section that consumes CPU and ignores cancellation.
                Thread.interrupted();
                Thread.onSpinWait();
            }
            return -1;
        }
    }
}
