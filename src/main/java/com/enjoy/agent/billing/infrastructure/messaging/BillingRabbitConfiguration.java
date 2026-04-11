package com.enjoy.agent.billing.infrastructure.messaging;

import com.enjoy.agent.billing.application.BillingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计费相关 RabbitMQ 基础拓扑。
 */
@Configuration
public class BillingRabbitConfiguration {

    @Bean
    public DirectExchange billingExchange(BillingProperties billingProperties) {
        return new DirectExchange(billingProperties.getRabbit().getExchange(), true, false);
    }

    @Bean
    public Queue billingUsageQueue(BillingProperties billingProperties) {
        return QueueBuilder.durable(billingProperties.getRabbit().getUsageQueue())
                .withArgument("x-dead-letter-exchange", billingProperties.getRabbit().getExchange())
                .withArgument("x-dead-letter-routing-key", billingProperties.getRabbit().getUsageDeadLetterRoutingKey())
                .build();
    }

    @Bean
    public Queue billingUsageDeadLetterQueue(BillingProperties billingProperties) {
        return QueueBuilder.durable(billingProperties.getRabbit().getUsageDeadLetterQueue()).build();
    }

    @Bean
    public Binding billingUsageBinding(
            @Qualifier("billingUsageQueue") Queue billingUsageQueue,
            @Qualifier("billingExchange") DirectExchange billingExchange,
            BillingProperties billingProperties
    ) {
        return BindingBuilder.bind(billingUsageQueue)
                .to(billingExchange)
                .with(billingProperties.getRabbit().getUsageRoutingKey());
    }

    @Bean
    public Binding billingUsageDeadLetterBinding(
            @Qualifier("billingUsageDeadLetterQueue") Queue billingUsageDeadLetterQueue,
            @Qualifier("billingExchange") DirectExchange billingExchange,
            BillingProperties billingProperties
    ) {
        return BindingBuilder.bind(billingUsageDeadLetterQueue)
                .to(billingExchange)
                .with(billingProperties.getRabbit().getUsageDeadLetterRoutingKey());
    }
}
