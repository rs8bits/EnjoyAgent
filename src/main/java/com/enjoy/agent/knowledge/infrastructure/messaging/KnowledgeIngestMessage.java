package com.enjoy.agent.knowledge.infrastructure.messaging;

import java.time.Instant;

/**
 * RabbitMQ 中只传递任务标识，文件内容始终保存在 MinIO。
 */
public record KnowledgeIngestMessage(
        int version,
        Long documentId,
        int deliveryAttempt,
        Instant occurredAt
) {

    public static final int CURRENT_VERSION = 1;

    public KnowledgeIngestMessage {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported knowledge ingest message version: " + version);
        }
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("Knowledge ingest documentId must be positive");
        }
        if (deliveryAttempt <= 0) {
            throw new IllegalArgumentException("Knowledge ingest deliveryAttempt must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Knowledge ingest occurredAt is required");
        }
    }

    public static KnowledgeIngestMessage firstAttempt(Long documentId, Instant occurredAt) {
        return new KnowledgeIngestMessage(CURRENT_VERSION, documentId, 1, occurredAt);
    }
}
