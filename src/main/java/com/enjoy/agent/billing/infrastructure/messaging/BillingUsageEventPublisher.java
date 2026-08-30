package com.enjoy.agent.billing.infrastructure.messaging;

import com.enjoy.agent.billing.application.BillingProperties;
import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
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
 * 发布官方模型使用计费事件。
 */
@Component
public class BillingUsageEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final BillingProperties billingProperties;

    public BillingUsageEventPublisher(RabbitTemplate rabbitTemplate, BillingProperties billingProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.billingProperties = billingProperties;
    }

    public void publish(BillingUsageOutbox outbox) {
        String messageId = "billing-usage-outbox-" + outbox.getId();
        Message message = MessageBuilder
                .withBody(String.valueOf(outbox.getUsageEventId()).getBytes(StandardCharsets.UTF_8))
                .setContentType("text/plain")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId)
                .setType("billing.usage.v1")
                .setTimestamp(new Date())
                .setHeader("usageEventId", outbox.getUsageEventId())
                .build();
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.send(
                billingProperties.getRabbit().getExchange(),
                billingProperties.getRabbit().getUsageRoutingKey(),
                message,
                correlationData
        );

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    billingProperties.getRabbit().getPublisherConfirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ rejected billing usage event: " + confirm.getReason());
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "RabbitMQ returned unroutable billing usage event: "
                                + correlationData.getReturned().getReplyText()
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for billing publisher confirm", ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Timed out waiting for billing publisher confirm", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("Billing publisher confirm failed", ex.getCause());
        }
    }
}
