package com.enjoy.agent.knowledge.infrastructure.messaging;

import com.enjoy.agent.knowledge.application.KnowledgeIngestProperties;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 等待 broker confirm，并把不可路由 return 视为发布失败。
 */
@Component
public class KnowledgeIngestPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeIngestProperties properties;

    public KnowledgeIngestPublisher(RabbitTemplate rabbitTemplate, KnowledgeIngestProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(KnowledgeIngestOutbox outbox) {
        String messageId = "knowledge-ingest-outbox-" + outbox.getId();
        Message message = MessageBuilder
                .withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId)
                .setType("knowledge.document.ingest.v1")
                .setTimestamp(new Date())
                .setHeader("documentId", outbox.getDocumentId())
                .setHeader("deliveryAttempt", outbox.getDeliveryAttempt())
                .build();
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.send(properties.getExchange(), properties.getRoutingKey(), message, correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.getPublisherConfirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ rejected knowledge ingest message: " + confirm.getReason());
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "RabbitMQ returned unroutable knowledge ingest message: "
                                + correlationData.getReturned().getReplyText()
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ publisher confirm", ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Timed out waiting for RabbitMQ publisher confirm", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("RabbitMQ publisher confirm failed", ex.getCause());
        }
    }
}
