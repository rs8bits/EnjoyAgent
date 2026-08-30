package com.enjoy.agent.billing.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.enjoy.agent.billing.application.BillingProperties;
import com.enjoy.agent.billing.domain.entity.BillingUsageOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class BillingUsageEventPublisherTest {

    @Test
    void publish_shouldWaitForPositiveBrokerConfirm() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BillingProperties properties = new BillingProperties();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(properties.getRabbit().getExchange()),
                eq(properties.getRabbit().getUsageRoutingKey()),
                any(Message.class),
                any(CorrelationData.class)
        );

        new BillingUsageEventPublisher(rabbitTemplate, properties).publish(outbox());
    }

    @Test
    void publish_shouldFailWhenBrokerNacks() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BillingProperties properties = new BillingProperties();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "disk alarm"));
            return null;
        }).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> new BillingUsageEventPublisher(rabbitTemplate, properties).publish(outbox()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void publish_shouldFailWhenMessageIsReturnedAsUnroutable() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BillingProperties properties = new BillingProperties();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    MessageBuilder.withBody(new byte[0]).build(),
                    312,
                    "NO_ROUTE",
                    properties.getRabbit().getExchange(),
                    properties.getRabbit().getUsageRoutingKey()
            ));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> new BillingUsageEventPublisher(rabbitTemplate, properties).publish(outbox()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unroutable");
    }

    private BillingUsageOutbox outbox() {
        BillingUsageOutbox outbox = new BillingUsageOutbox();
        outbox.setId(31L);
        outbox.setUsageEventId(41L);
        return outbox;
    }
}
