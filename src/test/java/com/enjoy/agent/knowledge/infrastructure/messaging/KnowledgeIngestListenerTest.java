package com.enjoy.agent.knowledge.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.knowledge.application.KnowledgeDocumentApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeIngestAlreadyFailedException;
import com.enjoy.agent.knowledge.application.KnowledgeIngestFailureDisposition;
import com.enjoy.agent.knowledge.application.KnowledgeIngestWorkSupersededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class KnowledgeIngestListenerTest {

    private final KnowledgeIngestMessageCodec codec = new KnowledgeIngestMessageCodec(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void shouldPassRedeliveryFlagToIdempotentProcessor() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        KnowledgeIngestListener listener = listener(service);
        Message message = message(42L, 1, true);

        listener.onMessage(message);

        verify(service).processAsyncDocument(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(true), anyString());
    }

    @Test
    void shouldAckOnlyAfterRetryWasPersisted() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        RuntimeException failure = new RuntimeException("embedding unavailable");
        doThrow(failure).when(service).processAsyncDocument(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false), anyString());
        when(service.handleAsyncProcessingFailure(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), anyString(), org.mockito.ArgumentMatchers.same(failure)))
                .thenReturn(KnowledgeIngestFailureDisposition.RETRY_SCHEDULED);

        listener(service).onMessage(message(42L, 1, false));

        verify(service).handleAsyncProcessingFailure(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), anyString(), org.mockito.ArgumentMatchers.same(failure));
    }

    @Test
    void shouldRejectExhaustedMessageToParkingQueue() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        RuntimeException failure = new RuntimeException("still unavailable");
        doThrow(failure).when(service).processAsyncDocument(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq(false), anyString());
        when(service.handleAsyncProcessingFailure(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(3), anyString(), org.mockito.ArgumentMatchers.same(failure)))
                .thenReturn(KnowledgeIngestFailureDisposition.PARK);

        assertThatThrownBy(() -> listener(service).onMessage(message(42L, 3, false)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void shouldRequeueWhenRetryStateCannotBePersisted() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        RuntimeException failure = new RuntimeException("embedding unavailable");
        doThrow(failure).when(service).processAsyncDocument(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false), anyString());
        when(service.handleAsyncProcessingFailure(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1), anyString(), org.mockito.ArgumentMatchers.same(failure)))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> listener(service).onMessage(message(42L, 1, false)))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    @Test
    void shouldParkMalformedPayload() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        KnowledgeIngestListener listener = listener(service);
        Message malformed = new Message("not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.onMessage(malformed))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void shouldAckSupersededWorkerWithoutChangingRetryState() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        doThrow(new KnowledgeIngestWorkSupersededException(42L, 1))
                .when(service).processAsyncDocument(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.eq(true),
                        anyString()
                );

        listener(service).onMessage(message(42L, 1, true));

        verify(service, org.mockito.Mockito.never()).handleAsyncProcessingFailure(any(), anyInt(), anyString(), any());
    }

    @Test
    void shouldRejectFailedRedeliveryAgainSoParkingIsNotLost() {
        KnowledgeDocumentApplicationService service = mock(KnowledgeDocumentApplicationService.class);
        doThrow(new KnowledgeIngestAlreadyFailedException(42L))
                .when(service).processAsyncDocument(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(3),
                        org.mockito.ArgumentMatchers.eq(true),
                        anyString()
                );

        assertThatThrownBy(() -> listener(service).onMessage(message(42L, 3, true)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private KnowledgeIngestListener listener(KnowledgeDocumentApplicationService service) {
        return new KnowledgeIngestListener(service, codec, new SimpleMeterRegistry());
    }

    private Message message(Long documentId, int attempt, boolean redelivered) {
        String payload = codec.encode(new KnowledgeIngestMessage(1, documentId, attempt, Instant.parse("2026-08-03T12:00:00Z")));
        MessageProperties properties = new MessageProperties();
        properties.setRedelivered(redelivered);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}
