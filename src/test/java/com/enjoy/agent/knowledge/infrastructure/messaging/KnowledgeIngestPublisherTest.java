package com.enjoy.agent.knowledge.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.enjoy.agent.knowledge.application.KnowledgeIngestProperties;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeIngestOutbox;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class KnowledgeIngestPublisherTest {

    @Test
    void shouldPublishPersistentMessageAndWaitForAck() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
        KnowledgeIngestPublisher publisher = new KnowledgeIngestPublisher(rabbitTemplate, properties);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(properties.getExchange()), eq(properties.getRoutingKey()), any(Message.class), any(CorrelationData.class));

        publisher.publish(outbox());

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(properties.getExchange()),
                eq(properties.getRoutingKey()),
                messageCaptor.capture(),
                any(CorrelationData.class)
        );
        Message message = messageCaptor.getValue();
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo("knowledge-ingest-outbox-9");
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"documentId\":42}");
    }

    @Test
    void shouldTreatMandatoryReturnAsPublishFailure() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeIngestProperties properties = new KnowledgeIngestProperties();
        KnowledgeIngestPublisher publisher = new KnowledgeIngestPublisher(rabbitTemplate, properties);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "missing", "missing"));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(properties.getExchange()), eq(properties.getRoutingKey()), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(outbox()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unroutable");
    }

    private KnowledgeIngestOutbox outbox() {
        KnowledgeIngestOutbox outbox = new KnowledgeIngestOutbox();
        outbox.setId(9L);
        outbox.setDocumentId(42L);
        outbox.setDeliveryAttempt(1);
        outbox.setPayload("{\"documentId\":42}");
        return outbox;
    }
}
