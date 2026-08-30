package com.enjoy.agent.billing.infrastructure.messaging;

import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费官方模型使用计费事件。
 */
@Component
public class BillingUsageEventListener {

    private final BillingUsageApplicationService billingUsageApplicationService;

    public BillingUsageEventListener(BillingUsageApplicationService billingUsageApplicationService) {
        this.billingUsageApplicationService = billingUsageApplicationService;
    }

    @RabbitListener(queues = "${enjoy.billing.rabbit.usage-queue:billing.usage.queue}")
    public void onUsageEvent(String payload) {
        final Long eventId;
        try {
            eventId = Long.valueOf(payload == null ? "" : payload.trim());
        } catch (RuntimeException ex) {
            // A poison message can never succeed on redelivery; route it to the configured DLQ.
            throw new AmqpRejectAndDontRequeueException("Invalid billing usage event payload", ex);
        }
        try {
            billingUsageApplicationService.processUsageEvent(eventId);
        } catch (RuntimeException ex) {
            if (billingUsageApplicationService.scheduleUsageEventRetry(eventId, ex)) {
                return;
            }
            throw new AmqpRejectAndDontRequeueException("Billing usage event processing failed", ex);
        }
    }
}
