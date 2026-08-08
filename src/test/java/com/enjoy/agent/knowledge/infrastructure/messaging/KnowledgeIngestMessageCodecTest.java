package com.enjoy.agent.knowledge.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KnowledgeIngestMessageCodecTest {

    private final KnowledgeIngestMessageCodec codec = new KnowledgeIngestMessageCodec(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void shouldRoundTripVersionedMessage() {
        KnowledgeIngestMessage original = new KnowledgeIngestMessage(1, 42L, 2, Instant.parse("2026-08-03T12:00:00Z"));

        KnowledgeIngestMessage decoded = codec.decode(codec.encode(original).getBytes(StandardCharsets.UTF_8));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void shouldRejectUnknownMessageVersion() {
        byte[] payload = """
                {"version":2,"documentId":42,"deliveryAttempt":1,"occurredAt":"2026-08-03T12:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid knowledge ingest message");
    }
}
