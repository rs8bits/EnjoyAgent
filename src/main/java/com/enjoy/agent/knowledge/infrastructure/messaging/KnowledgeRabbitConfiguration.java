package com.enjoy.agent.knowledge.infrastructure.messaging;

import com.enjoy.agent.knowledge.application.KnowledgeIngestProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识文档导入的独立 RabbitMQ 拓扑和固定并发 Worker。
 */
@Configuration
@EnableScheduling
public class KnowledgeRabbitConfiguration {

    @Bean
    public DirectExchange knowledgeIngestExchange(KnowledgeIngestProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue knowledgeIngestQueue(KnowledgeIngestProperties properties) {
        return QueueBuilder.durable(properties.getQueue())
                .withArgument("x-dead-letter-exchange", properties.getExchange())
                .withArgument("x-dead-letter-routing-key", properties.getParkingRoutingKey())
                .build();
    }

    @Bean
    public Queue knowledgeIngestParkingQueue(KnowledgeIngestProperties properties) {
        return QueueBuilder.durable(properties.getParkingQueue()).build();
    }

    @Bean
    public Binding knowledgeIngestBinding(
            @Qualifier("knowledgeIngestQueue") Queue queue,
            @Qualifier("knowledgeIngestExchange") DirectExchange exchange,
            KnowledgeIngestProperties properties
    ) {
        return BindingBuilder.bind(queue).to(exchange).with(properties.getRoutingKey());
    }

    @Bean
    public Binding knowledgeIngestParkingBinding(
            @Qualifier("knowledgeIngestParkingQueue") Queue queue,
            @Qualifier("knowledgeIngestExchange") DirectExchange exchange,
            KnowledgeIngestProperties properties
    ) {
        return BindingBuilder.bind(queue).to(exchange).with(properties.getParkingRoutingKey());
    }

    @Bean(name = "knowledgeIngestListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory knowledgeIngestListenerContainerFactory(
            ConnectionFactory connectionFactory,
            KnowledgeIngestProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(properties.getWorkerConcurrency());
        factory.setMaxConcurrentConsumers(properties.getWorkerConcurrency());
        factory.setPrefetchCount(properties.getPrefetch());
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

}
