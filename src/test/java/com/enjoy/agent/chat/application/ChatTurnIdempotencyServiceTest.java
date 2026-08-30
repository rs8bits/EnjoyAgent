package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.api.request.SendChatMessageRequest;
import com.enjoy.agent.chat.api.response.ChatTurnResponse;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.chat.domain.entity.ChatTurnRequest;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatTurnRequestRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ChatTurnIdempotencyServiceTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatTurnRequestRepository requestRepository;

    @Test
    void concurrentDifferentKeysForSameSessionYieldOneOwnerAndOneConflict() throws Exception {
        when(sessionRepository.findForUpdateByIdAndTenant_Id(10L, 1L))
                .thenReturn(Optional.of(new ChatSession()));
        when(requestRepository.findBySessionIdAndIdempotencyKey(any(), any()))
                .thenReturn(Optional.empty());
        CyclicBarrier bothReadActiveFence = new CyclicBarrier(2);
        when(requestRepository.findFirstBySessionIdAndStatusOrderByIdAsc(10L, "RUNNING"))
                .thenAnswer(invocation -> {
                    bothReadActiveFence.await();
                    return Optional.empty();
                });
        AtomicReference<ChatTurnRequest> winner = new AtomicReference<>();
        AtomicLong ids = new AtomicLong(1L);
        when(requestRepository.saveAndFlush(any(ChatTurnRequest.class))).thenAnswer(invocation -> {
            ChatTurnRequest candidate = invocation.getArgument(0);
            if (!winner.compareAndSet(null, candidate)) {
                throw new DataIntegrityViolationException("partial unique index conflict");
            }
            candidate.setId(ids.getAndIncrement());
            return candidate;
        });
        ChatTurnIdempotencyService service = new ChatTurnIdempotencyService(
                sessionRepository,
                requestRepository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
        );
        SendChatMessageRequest request = new SendChatMessageRequest("hello", List.of());
        Callable<Object> first = () -> callBegin(service, "request-a", request);
        Callable<Object> second = () -> callBegin(service, "request-b", request);

        List<java.util.concurrent.Future<Object>> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = executor.invokeAll(List.of(first, second));
        }
        List<Object> values = List.of(results.get(0).get(), results.get(1).get());

        assertThat(values.stream().filter(ChatTurnIdempotencyService.TurnClaim.class::isInstance).count())
                .isEqualTo(1);
        assertThat(values.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).map(ApiException::getCode).toList())
                .containsExactly("CHAT_SESSION_BUSY");
    }

    @Test
    void completedIdempotencyKeyReturnsExactlyTheStoredResultWithoutNewOwnership() {
        AtomicReference<ChatTurnRequest> stored = new AtomicReference<>();
        when(sessionRepository.findForUpdateByIdAndTenant_Id(10L, 1L))
                .thenReturn(Optional.of(new ChatSession()));
        when(requestRepository.findBySessionIdAndIdempotencyKey(10L, "same-request"))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(requestRepository.findFirstBySessionIdAndStatusOrderByIdAsc(10L, "RUNNING"))
                .thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any(ChatTurnRequest.class))).thenAnswer(invocation -> {
            ChatTurnRequest request = invocation.getArgument(0);
            request.setId(44L);
            stored.set(request);
            return request;
        });
        when(requestRepository.findByIdAndOwnerTokenAndStatus(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(requestRepository.save(any(ChatTurnRequest.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        ChatTurnIdempotencyService service = new ChatTurnIdempotencyService(
                sessionRepository,
                requestRepository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
        );
        SendChatMessageRequest request = new SendChatMessageRequest("hello", List.of());
        ChatTurnResponse response = new ChatTurnResponse(
                10L, null, null, "OPENAI", "model", "USER", 3L, 20L, 1, 2, 3, null);

        ChatTurnIdempotencyService.TurnClaim first = service.begin(
                1L, 2L, 10L, "same-request", request);
        service.complete(first.handle(), response);
        ChatTurnIdempotencyService.TurnClaim replay = service.begin(
                1L, 2L, 10L, "same-request", request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.cachedResponse()).isEqualTo(response);
    }

    @Test
    void failedAttemptRetainsItsUserMessageForTheNextLeaseOwner() {
        AtomicReference<ChatTurnRequest> stored = new AtomicReference<>();
        when(sessionRepository.findForUpdateByIdAndTenant_Id(10L, 1L))
                .thenReturn(Optional.of(new ChatSession()));
        when(requestRepository.findBySessionIdAndIdempotencyKey(10L, "retry-request"))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(requestRepository.findFirstBySessionIdAndStatusOrderByIdAsc(10L, "RUNNING"))
                .thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any(ChatTurnRequest.class))).thenAnswer(invocation -> {
            ChatTurnRequest request = invocation.getArgument(0);
            request.setId(55L);
            stored.set(request);
            return request;
        });
        when(requestRepository.findByIdAndOwnerTokenAndStatus(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(requestRepository.save(any(ChatTurnRequest.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        ChatTurnIdempotencyService service = new ChatTurnIdempotencyService(
                sessionRepository,
                requestRepository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
        );
        SendChatMessageRequest request = new SendChatMessageRequest("hello", List.of("approval-old"));

        ChatTurnIdempotencyService.TurnClaim first = service.begin(
                1L, 2L, 10L, "retry-request", request);
        service.attachUserMessage(first.handle(), 777L);
        service.fail(first.handle());
        ChatTurnIdempotencyService.TurnClaim retry = service.begin(
                1L, 2L, 10L, "retry-request",
                new SendChatMessageRequest("hello", List.of("approval-new")));

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.userMessageId()).isEqualTo(777L);
        assertThat(retry.handle().ownerToken()).isNotEqualTo(first.handle().ownerToken());
    }

    private Object callBegin(
            ChatTurnIdempotencyService service,
            String key,
            SendChatMessageRequest request
    ) {
        try {
            return service.begin(1L, 2L, 10L, key, request);
        } catch (ApiException ex) {
            return ex;
        }
    }
}
