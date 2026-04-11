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
        Long eventId = Long.valueOf(payload.trim());
        try {
            billingUsageApplicationService.processUsageEvent(eventId);
        } catch (RuntimeException ex) {
            billingUsageApplicationService.markUsageEventFailed(eventId, ex);
            throw new AmqpRejectAndDontRequeueException("Billing usage event processing failed", ex);
        }
    }
}
