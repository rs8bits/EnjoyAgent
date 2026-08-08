package com.enjoy.agent.knowledge.infrastructure.messaging;

import com.enjoy.agent.knowledge.application.KnowledgeDocumentApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeIngestAlreadyFailedException;
import com.enjoy.agent.knowledge.application.KnowledgeIngestFailureDisposition;
import com.enjoy.agent.knowledge.application.KnowledgeIngestWorkSupersededException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 固定并发消费文档导入任务。正常重试先可靠写回 Outbox，再 ACK 当前消息。
 */
@Component
public class KnowledgeIngestListener {

    private final KnowledgeDocumentApplicationService documentService;
    private final KnowledgeIngestMessageCodec messageCodec;
    private final Counter completed;
    private final Counter retried;
    private final Counter parked;

    public KnowledgeIngestListener(
            KnowledgeDocumentApplicationService documentService,
            KnowledgeIngestMessageCodec messageCodec,
            MeterRegistry meterRegistry
    ) {
        this.documentService = documentService;
        this.messageCodec = messageCodec;
        this.completed = counter(meterRegistry, "success");
        this.retried = counter(meterRegistry, "retry_scheduled");
        this.parked = counter(meterRegistry, "parked");
    }

    @RabbitListener(
            queues = "${enjoy.knowledge.ingest.queue:knowledge.document.ingest.queue}",
            containerFactory = "knowledgeIngestListenerContainerFactory"
    )
    public void onMessage(Message rawMessage) {
        KnowledgeIngestMessage message;
        try {
            message = messageCodec.decode(rawMessage.getBody());
        } catch (RuntimeException ex) {
            parked.increment();
            throw new AmqpRejectAndDontRequeueException("Invalid knowledge ingest message", ex);
        }

        String processingToken = UUID.randomUUID().toString();
        try {
            documentService.processAsyncDocument(
                    message.documentId(),
                    message.deliveryAttempt(),
                    Boolean.TRUE.equals(rawMessage.getMessageProperties().isRedelivered()),
                    processingToken
            );
            completed.increment();
        } catch (KnowledgeIngestWorkSupersededException superseded) {
            return;
        } catch (KnowledgeIngestAlreadyFailedException alreadyFailed) {
            parked.increment();
            throw new AmqpRejectAndDontRequeueException(
                    "Knowledge document is already failed",
                    alreadyFailed
            );
        } catch (RuntimeException processingFailure) {
            KnowledgeIngestFailureDisposition disposition;
            try {
                disposition = documentService.handleAsyncProcessingFailure(
                        message.documentId(),
                        message.deliveryAttempt(),
                        processingToken,
                        processingFailure
                );
            } catch (RuntimeException retryPersistenceFailure) {
                throw new ImmediateRequeueAmqpException(
                        "Failed to persist knowledge ingest retry state",
                        retryPersistenceFailure
                );
            }

            if (disposition == KnowledgeIngestFailureDisposition.RETRY_SCHEDULED) {
                retried.increment();
                return;
            }
            if (disposition == KnowledgeIngestFailureDisposition.IGNORE) {
                return;
            }
            parked.increment();
            throw new AmqpRejectAndDontRequeueException(
                    "Knowledge document ingest exhausted retries",
                    processingFailure
            );
        }
    }

    private Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("enjoy.mq.consume")
                .tag("eventType", "knowledge_document_ingest")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
