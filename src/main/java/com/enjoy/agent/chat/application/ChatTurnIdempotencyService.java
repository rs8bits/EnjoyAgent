package com.enjoy.agent.chat.application;

import com.enjoy.agent.chat.api.request.SendChatMessageRequest;
import com.enjoy.agent.chat.api.response.ChatTurnResponse;
import com.enjoy.agent.chat.domain.entity.ChatTurnRequest;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatTurnRequestRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Persistent idempotency result cache and one-active-turn-per-session fence. */
@Service
public class ChatTurnIdempotencyService {

    private static final String RUNNING = "RUNNING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";
    private static final Duration TURN_LEASE = Duration.ofMinutes(15);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ChatSessionRepository sessionRepository;
    private final ChatTurnRequestRepository requestRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChatTurnIdempotencyService(
            ChatSessionRepository sessionRepository,
            ChatTurnRequestRepository requestRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public TurnClaim begin(
            Long tenantId,
            Long userId,
            Long sessionId,
            String suppliedKey,
            SendChatMessageRequest request
    ) {
        sessionRepository.findForUpdateByIdAndTenant_Id(sessionId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "CHAT_SESSION_NOT_FOUND", "Chat session not found", HttpStatus.NOT_FOUND));
        String key = normalizeKey(suppliedKey);
        String requestHash = requestHash(request);
        Instant now = clock.instant();
        ChatTurnRequest existing = requestRepository
                .findBySessionIdAndIdempotencyKey(sessionId, key).orElse(null);
        if (existing != null) {
            requireSameRequest(existing, tenantId, userId, requestHash);
            if (COMPLETED.equals(existing.getStatus())) {
                return new TurnClaim(null, readResponse(existing), key, existing.getUserMessageId());
            }
            if (RUNNING.equals(existing.getStatus()) && leaseIsActive(existing, now)) {
                throw conflict("CHAT_TURN_IN_PROGRESS", "This idempotent chat turn is still in progress");
            }
            if (RUNNING.equals(existing.getStatus())) {
                markFailed(existing);
                requestRepository.saveAndFlush(existing);
            }
        }

        ChatTurnRequest active = requestRepository
                .findFirstBySessionIdAndStatusOrderByIdAsc(sessionId, RUNNING).orElse(null);
        if (active != null) {
            if (leaseIsActive(active, now)) {
                throw conflict("CHAT_SESSION_BUSY", "Another chat turn is already running for this session");
            }
            markFailed(active);
            requestRepository.saveAndFlush(active);
        }

        ChatTurnRequest claimed = existing == null ? new ChatTurnRequest() : existing;
        String ownerToken = UUID.randomUUID().toString();
        claimed.setTenantId(tenantId);
        claimed.setUserId(userId);
        claimed.setSessionId(sessionId);
        claimed.setIdempotencyKey(key);
        claimed.setRequestHash(requestHash);
        claimed.setOwnerToken(ownerToken);
        claimed.setStatus(RUNNING);
        claimed.setResponseJson(null);
        claimed.setLeaseExpiresAt(now.plus(TURN_LEASE));
        try {
            ChatTurnRequest saved = requestRepository.saveAndFlush(claimed);
            return new TurnClaim(
                    new TurnHandle(saved.getId(), ownerToken), null, key, saved.getUserMessageId());
        } catch (DataIntegrityViolationException ex) {
            throw conflict("CHAT_SESSION_BUSY", "Another chat turn is already running for this session");
        }
    }

    /**
     * Durably binds the idempotency request to its single USER message. This call is
     * intended to join the same transaction that inserts the message.
     */
    @Transactional
    public void attachUserMessage(TurnHandle handle, Long userMessageId) {
        if (handle == null || userMessageId == null) {
            throw conflict("CHAT_TURN_FENCE_LOST", "Chat turn ownership was not available");
        }
        ChatTurnRequest request = requestRepository
                .findByIdAndOwnerTokenAndStatus(handle.requestId(), handle.ownerToken(), RUNNING)
                .orElseThrow(() -> conflict(
                        "CHAT_TURN_FENCE_LOST", "Chat turn ownership expired before message persistence"));
        if (request.getUserMessageId() != null
                && !Objects.equals(request.getUserMessageId(), userMessageId)) {
            throw conflict("CHAT_TURN_FENCE_LOST", "Chat turn is already bound to another message");
        }
        request.setUserMessageId(userMessageId);
        requestRepository.save(request);
    }

    @Transactional
    public void complete(TurnHandle handle, ChatTurnResponse response) {
        if (handle == null) {
            return;
        }
        ChatTurnRequest request = requestRepository
                .findByIdAndOwnerTokenAndStatus(handle.requestId(), handle.ownerToken(), RUNNING)
                .orElseThrow(() -> conflict(
                        "CHAT_TURN_FENCE_LOST", "Chat turn ownership expired before completion"));
        try {
            request.setResponseJson(objectMapper.writeValueAsString(response));
        } catch (Exception ex) {
            throw new ApiException("CHAT_TURN_SERIALIZATION_FAILED",
                    "Failed to persist idempotent chat result", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        request.setStatus(COMPLETED);
        request.setOwnerToken(null);
        request.setLeaseExpiresAt(null);
        requestRepository.save(request);
    }

    @Transactional
    public void fail(TurnHandle handle) {
        if (handle == null) {
            return;
        }
        requestRepository.findByIdAndOwnerTokenAndStatus(handle.requestId(), handle.ownerToken(), RUNNING)
                .ifPresent(request -> {
                    markFailed(request);
                    requestRepository.save(request);
                });
    }

    private void requireSameRequest(
            ChatTurnRequest existing,
            Long tenantId,
            Long userId,
            String requestHash
    ) {
        if (!Objects.equals(existing.getTenantId(), tenantId)
                || !Objects.equals(existing.getUserId(), userId)
                || !MessageDigest.isEqual(
                        existing.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                        requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different chat request");
        }
    }

    private ChatTurnResponse readResponse(ChatTurnRequest request) {
        try {
            return objectMapper.readValue(request.getResponseJson(), ChatTurnResponse.class);
        } catch (Exception ex) {
            throw new ApiException("CHAT_TURN_RESULT_CORRUPTED",
                    "Stored idempotent chat result could not be read", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean leaseIsActive(ChatTurnRequest request, Instant now) {
        return request.getLeaseExpiresAt() != null && request.getLeaseExpiresAt().isAfter(now);
    }

    private void markFailed(ChatTurnRequest request) {
        request.setStatus(FAILED);
        request.setOwnerToken(null);
        request.setLeaseExpiresAt(null);
    }

    private String normalizeKey(String suppliedKey) {
        if (!StringUtils.hasText(suppliedKey)) {
            return "auto_" + UUID.randomUUID();
        }
        String normalized = suppliedKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(normalized).matches()) {
            throw new ApiException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must contain 1-128 letters, digits, '.', '_', ':' or '-'",
                    HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String requestHash(SendChatMessageRequest request) {
        try {
            // Approval tokens are short-lived credentials, not semantic request data.
            // A failed turn may therefore be explicitly re-approved without changing
            // its idempotency identity or inserting another USER message.
            Map<String, Object> semanticRequest = new LinkedHashMap<>();
            semanticRequest.put("content", request.content());
            byte[] canonical = objectMapper.writeValueAsBytes(semanticRequest);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash chat request", ex);
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(code, message, HttpStatus.CONFLICT);
    }

    public record TurnHandle(Long requestId, String ownerToken) {
    }

    public record TurnClaim(
            TurnHandle handle,
            ChatTurnResponse cachedResponse,
            String idempotencyKey,
            Long userMessageId
    ) {
        public boolean replayed() {
            return cachedResponse != null;
        }
    }
}
