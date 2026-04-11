package com.enjoy.agent.billing.infrastructure.messaging;

import com.enjoy.agent.billing.application.BillingProperties;
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

    public void publish(Long eventId) {
        rabbitTemplate.convertAndSend(
                billingProperties.getRabbit().getExchange(),
                billingProperties.getRabbit().getUsageRoutingKey(),
                String.valueOf(eventId)
        );
    }
}
