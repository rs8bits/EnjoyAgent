package com.enjoy.agent.chat.infrastructure.persistence;

import com.enjoy.agent.chat.domain.entity.ChatTurnRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatTurnRequestRepository extends JpaRepository<ChatTurnRequest, Long> {

    Optional<ChatTurnRequest> findBySessionIdAndIdempotencyKey(Long sessionId, String idempotencyKey);

    Optional<ChatTurnRequest> findFirstBySessionIdAndStatusOrderByIdAsc(Long sessionId, String status);

    Optional<ChatTurnRequest> findByIdAndOwnerTokenAndStatus(Long id, String ownerToken, String status);

    boolean existsBySessionIdAndStatus(Long sessionId, String status);
}
