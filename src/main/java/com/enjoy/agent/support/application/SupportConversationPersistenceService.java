package com.enjoy.agent.support.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.support.api.response.SupportConversationResponse;
import com.enjoy.agent.support.api.response.SupportMessageResponse;
import com.enjoy.agent.support.api.response.SupportMessageSliceResponse;
import com.enjoy.agent.support.domain.entity.SupportConversation;
import com.enjoy.agent.support.domain.entity.SupportMessage;
import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportMessageRole;
import com.enjoy.agent.support.domain.enums.SupportRouteType;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import com.enjoy.agent.support.infrastructure.persistence.SupportConversationRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportMessageRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportTurnRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客服会话、Turn 和消息的事务边界。
 *
 * <p>模型与 Skill 调用耗时较长，绝不能把数据库事务一直保持到网络请求完成。
 * 因此编排器只在“开始、记录路由、完成、失败”等短步骤调用本服务。</p>
 */
@Service
public class SupportConversationPersistenceService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final SupportConversationRepository conversationRepository;
    private final SupportTurnRepository turnRepository;
    private final SupportMessageRepository messageRepository;
    private final SupportProperties properties;

    public SupportConversationPersistenceService(
            SupportConversationRepository conversationRepository,
            SupportTurnRepository turnRepository,
            SupportMessageRepository messageRepository,
            SupportProperties properties
    ) {
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    /** PostgreSQL ON CONFLICT 保证 user + tenant 的首次并发创建仍只产生一条会话。 */
    @Transactional
    public SupportConversationResponse getOrCreate(AuthenticatedUser user) {
        conversationRepository.insertIfAbsent(user.tenantId(), user.userId());
        SupportConversation conversation = conversationRepository
                .findByTenant_IdAndUser_Id(user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_CONVERSATION_CREATE_FAILED",
                        "客服会话创建失败，请稍后重试",
                        HttpStatus.INTERNAL_SERVER_ERROR
                ));
        return toConversationResponse(conversation);
    }

    /**
     * 原子创建 Turn 和用户消息，并建立两层并发保护：
     * 1) 悲观锁串行化同一会话的序号分配；2) 数据库部分唯一索引阻止两个 RUNNING。
     */
    @Transactional
    public SupportTurnStart beginTurn(
            AuthenticatedUser user,
            Long conversationId,
            String idempotencyKey,
            String normalizedContent
    ) {
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = sha256(normalizedContent);
        SupportConversation conversation = requireOwnedConversationForUpdate(user, conversationId);

        SupportTurn existing = turnRepository
                .findByConversation_IdAndIdempotencyKey(conversationId, key)
                .orElse(null);
        if (existing != null) {
            return replayExistingTurn(existing, requestHash);
        }
        SupportTurn running = turnRepository
                .findFirstByConversation_IdAndStatusOrderByCreatedAtAsc(
                        conversationId, SupportTurnStatus.RUNNING)
                .orElse(null);
        if (running != null) {
            if (!leaseExpired(running)) {
                throw new ApiException(
                        "SUPPORT_TURN_ALREADY_RUNNING",
                        "当前客服会话仍在处理上一条消息，请稍后再试",
                        HttpStatus.CONFLICT
                );
            }
            markExpired(running);
            // 强制先发出 UPDATE，再 INSERT 新 RUNNING，避免部分唯一索引在 flush
            // 排序时短暂看到两条 RUNNING。
            turnRepository.flush();
        }

        SupportTurn turn = new SupportTurn();
        turn.setId(UUID.randomUUID());
        turn.setConversation(conversation);
        turn.setIdempotencyKey(key);
        turn.setRequestHash(requestHash);
        turn.setOwnerToken(UUID.randomUUID());
        turn.setStatus(SupportTurnStatus.RUNNING);
        turn.setLeaseExpiresAt(newLeaseExpiry());
        turnRepository.saveAndFlush(turn);

        SupportMessage message = new SupportMessage();
        message.setConversation(conversation);
        message.setTurn(turn);
        message.setSequence(conversation.allocateNextMessageSequence());
        message.setRole(SupportMessageRole.USER);
        message.setContent(normalizedContent);
        messageRepository.saveAndFlush(message);

        return new SupportTurnStart(
                conversation.getId(), turn.getId(), turn.getOwnerToken(), false,
                toMessageResponse(message), null);
    }

    @Transactional
    public void recordRouting(
            AuthenticatedUser user,
            UUID turnId,
            UUID ownerToken,
            String intentCode,
            SupportRouteType routeType
    ) {
        SupportTurn turn = requireOwnedRunningTurn(user, turnId, ownerToken);
        turn.setIntentCode(intentCode);
        turn.setRouteType(routeType);
    }

    /** 最终回答先落库并提交，调用方随后才发送 completed SSE。 */
    @Transactional
    public SupportMessageResponse completeTurn(
            AuthenticatedUser user,
            Long conversationId,
            UUID turnId,
            UUID ownerToken,
            String assistantContent,
            SupportModelUsage usage
    ) {
        // beginTurn 的加锁顺序也是“会话 -> Turn”。保持一致可以同时保护消息序号，
        // 并避免 completeTurn 与租约接管互相反向等待而形成数据库死锁。
        SupportConversation conversation = requireOwnedConversationForUpdate(user, conversationId);
        SupportTurn turn = requireOwnedRunningTurn(user, turnId, ownerToken);
        if (!turn.getConversation().getId().equals(conversation.getId())) {
            throw new ApiException(
                    "SUPPORT_TURN_NOT_FOUND", "客服请求不存在", HttpStatus.NOT_FOUND);
        }

        SupportMessage message = new SupportMessage();
        message.setConversation(conversation);
        message.setTurn(turn);
        message.setSequence(conversation.allocateNextMessageSequence());
        message.setRole(SupportMessageRole.ASSISTANT);
        message.setContent(assistantContent);
        messageRepository.saveAndFlush(message);

        if (usage != null) {
            turn.setModelProvider(usage.provider());
            turn.setModelName(usage.modelName());
            turn.setOfficialModelConfigId(usage.officialModelConfigId());
            turn.setModelLatencyMs(usage.latencyMs());
            turn.setPromptTokens(usage.promptTokens());
            turn.setCompletionTokens(usage.completionTokens());
            turn.setTotalTokens(usage.totalTokens());
        }
        turn.setStatus(SupportTurnStatus.COMPLETED);
        turn.setCompletedAt(Instant.now());
        turn.setLeaseExpiresAt(null);
        return toMessageResponse(message);
    }

    @Transactional
    public void failTurn(
            AuthenticatedUser user,
            UUID turnId,
            UUID ownerToken,
            String errorCode,
            String safeMessage
    ) {
        SupportTurn turn = requireOwnedTurn(user, turnId);
        // 旧执行者在租约接管后可能收到超时/断连异常。这里必须静默忽略它的失败
        // 写入，否则会把新执行者刚接管的 RUNNING Turn 错误标记为 FAILED。
        if (turn.getStatus() != SupportTurnStatus.RUNNING
                || ownerToken == null
                || !ownerToken.equals(turn.getOwnerToken())) {
            return;
        }
        turn.setStatus(SupportTurnStatus.FAILED);
        turn.setErrorCode(truncate(errorCode, 64));
        turn.setErrorMessage(truncate(safeMessage, 512));
        turn.setCompletedAt(Instant.now());
        turn.setLeaseExpiresAt(null);
    }

    @Transactional(readOnly = true)
    public SupportMessageSliceResponse listMessages(
            AuthenticatedUser user,
            Long conversationId,
            Long beforeSequence,
            int limit
    ) {
        requireOwnedConversation(user, conversationId);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        Slice<SupportMessage> slice;
        if (beforeSequence == null) {
            slice = messageRepository.findAllByConversation_IdOrderBySequenceDesc(
                    conversationId, PageRequest.of(0, safeLimit));
        } else {
            if (beforeSequence <= 0) {
                throw new ApiException(
                        "SUPPORT_MESSAGE_CURSOR_INVALID",
                        "消息游标必须大于 0",
                        HttpStatus.BAD_REQUEST
                );
            }
            slice = messageRepository.findAllByConversation_IdAndSequenceLessThanOrderBySequenceDesc(
                    conversationId, beforeSequence, PageRequest.of(0, safeLimit));
        }
        List<SupportMessageResponse> items = new ArrayList<>(
                slice.getContent().stream().map(this::toMessageResponse).toList());
        Collections.reverse(items);
        Long nextCursor = slice.hasNext() && !items.isEmpty() ? items.getFirst().sequence() : null;
        return new SupportMessageSliceResponse(List.copyOf(items), slice.hasNext(), nextCursor);
    }

    /** 构建模型窗口时只读取最近 N 条，并再次检查会话归属。 */
    @Transactional(readOnly = true)
    public List<SupportMessageResponse> latestMessages(
            AuthenticatedUser user,
            Long conversationId,
            UUID currentTurnId,
            int limit
    ) {
        requireOwnedConversation(user, conversationId);
        List<SupportMessageResponse> messages = new ArrayList<>(messageRepository.findLatestForModel(
                        conversationId,
                        currentTurnId,
                        SupportTurnStatus.COMPLETED,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
                .stream()
                .map(this::toMessageResponse)
                .toList());
        Collections.reverse(messages);
        // 固定条数窗口可能恰好从某个已完成 Turn 的 ASSISTANT 消息中间截入。
        // 丢弃这条孤立回答，避免模型看到没有对应用户问题的历史。
        if (messages.size() > 1 && "ASSISTANT".equals(messages.getFirst().role())) {
            messages.removeFirst();
        }
        return List.copyOf(messages);
    }

    /**
     * 确认接口幂等重放时读取那次确认执行写入的正式消息。查询仍然先验证 Turn 属于
     * 当前 tenant + user，避免仅凭一个 UUID 枚举其他用户的操作结果。
     */
    @Transactional(readOnly = true)
    public List<SupportMessageResponse> messagesForTurn(AuthenticatedUser user, UUID turnId) {
        requireOwnedTurn(user, turnId);
        return messageRepository.findAllByTurn_IdOrderBySequenceAsc(turnId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    private SupportTurnStart replayExistingTurn(SupportTurn existing, String requestHash) {
        if (!MessageDigest.isEqual(
                existing.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(
                    "SUPPORT_IDEMPOTENCY_KEY_REUSED",
                    "同一幂等键不能用于不同的客服消息",
                    HttpStatus.CONFLICT
            );
        }
        if (existing.getStatus() == SupportTurnStatus.RUNNING && !leaseExpired(existing)) {
            throw new ApiException(
                    "SUPPORT_TURN_ALREADY_RUNNING",
                    "该消息仍在处理中，请稍后查看历史记录",
                    HttpStatus.CONFLICT
            );
        }
        if (existing.getStatus() == SupportTurnStatus.FAILED
                && turnRepository.existsByConversation_IdAndStatus(
                        existing.getConversation().getId(), SupportTurnStatus.RUNNING)) {
            throw new ApiException(
                    "SUPPORT_TURN_ALREADY_RUNNING",
                    "当前客服会话仍在处理另一条消息，请稍后再试",
                    HttpStatus.CONFLICT
            );
        }
        if (existing.getStatus() == SupportTurnStatus.FAILED
                || existing.getStatus() == SupportTurnStatus.RUNNING) {
            // 相同幂等键、相同内容可以接管失败或租约过期的 Turn。复用原用户消息，
            // 因此重试不会在完整会话历史中写出重复问题。
            existing.setStatus(SupportTurnStatus.RUNNING);
            existing.setErrorCode(null);
            existing.setErrorMessage(null);
            existing.setCompletedAt(null);
            existing.setOwnerToken(UUID.randomUUID());
            existing.setLeaseExpiresAt(newLeaseExpiry());
            SupportMessageResponse userMessage = messageRepository
                    .findAllByTurn_IdOrderBySequenceAsc(existing.getId()).stream()
                    .filter(message -> message.getRole() == SupportMessageRole.USER)
                    .findFirst().map(this::toMessageResponse)
                    .orElseThrow(() -> new ApiException(
                            "SUPPORT_TURN_ARCHIVE_INCOMPLETE",
                            "历史客服请求归档不完整",
                            HttpStatus.INTERNAL_SERVER_ERROR
                    ));
            return new SupportTurnStart(
                    existing.getConversation().getId(), existing.getId(), existing.getOwnerToken(),
                    false, userMessage, null);
        }
        List<SupportMessage> messages = messageRepository.findAllByTurn_IdOrderBySequenceAsc(existing.getId());
        SupportMessageResponse userMessage = messages.stream()
                .filter(message -> message.getRole() == SupportMessageRole.USER)
                .findFirst().map(this::toMessageResponse).orElse(null);
        SupportMessageResponse assistantMessage = messages.stream()
                .filter(message -> message.getRole() == SupportMessageRole.ASSISTANT)
                .findFirst().map(this::toMessageResponse).orElse(null);
        if (userMessage == null || assistantMessage == null) {
            throw new ApiException(
                    "SUPPORT_TURN_ARCHIVE_INCOMPLETE",
                    "历史客服请求归档不完整",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        return new SupportTurnStart(
                existing.getConversation().getId(), existing.getId(), existing.getOwnerToken(),
                true, userMessage, assistantMessage);
    }

    private SupportConversation requireOwnedConversationForUpdate(AuthenticatedUser user, Long conversationId) {
        return conversationRepository.findOwnedByIdForUpdate(
                        conversationId, user.tenantId(), user.userId())
                .orElseThrow(this::conversationNotFound);
    }

    private SupportConversation requireOwnedConversation(AuthenticatedUser user, Long conversationId) {
        return conversationRepository.findByTenant_IdAndUser_Id(user.tenantId(), user.userId())
                .filter(conversation -> conversation.getId().equals(conversationId))
                .orElseThrow(this::conversationNotFound);
    }

    private SupportTurn requireOwnedTurn(AuthenticatedUser user, UUID turnId) {
        return turnRepository.findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                        turnId, user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_TURN_NOT_FOUND", "客服请求不存在", HttpStatus.NOT_FOUND));
    }

    private SupportTurn requireOwnedRunningTurn(
            AuthenticatedUser user,
            UUID turnId,
            UUID ownerToken
    ) {
        SupportTurn turn = requireOwnedTurn(user, turnId);
        if (turn.getStatus() != SupportTurnStatus.RUNNING
                || ownerToken == null
                || !ownerToken.equals(turn.getOwnerToken())) {
            throw new ApiException(
                    "SUPPORT_TURN_OWNERSHIP_LOST",
                    "客服请求执行权已失效，请刷新历史记录",
                    HttpStatus.CONFLICT
            );
        }
        return turn;
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new ApiException(
                    "SUPPORT_IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 必须为 8 到 128 位字母、数字或 . _ : -",
                    HttpStatus.BAD_REQUEST
            );
        }
        return value;
    }

    private Instant newLeaseExpiry() {
        return Instant.now().plusMillis(properties.getEmitterTimeoutMs() + 60_000);
    }

    private boolean leaseExpired(SupportTurn turn) {
        return turn.getLeaseExpiresAt() == null || !turn.getLeaseExpiresAt().isAfter(Instant.now());
    }

    private void markExpired(SupportTurn turn) {
        turn.setStatus(SupportTurnStatus.FAILED);
        turn.setErrorCode("SUPPORT_TURN_LEASE_EXPIRED");
        turn.setErrorMessage("客服请求执行租约已过期");
        turn.setCompletedAt(Instant.now());
        turn.setLeaseExpiresAt(null);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private ApiException conversationNotFound() {
        // 统一返回 404，不告诉调用方这个 ID 是否存在于其他租户。
        return new ApiException(
                "SUPPORT_CONVERSATION_NOT_FOUND", "客服会话不存在", HttpStatus.NOT_FOUND);
    }

    private SupportConversationResponse toConversationResponse(SupportConversation conversation) {
        return new SupportConversationResponse(
                conversation.getId(),
                conversation.getTenant().getId(),
                conversation.getUser().getId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                conversation.getLastMessageSequence()
        );
    }

    private SupportMessageResponse toMessageResponse(SupportMessage message) {
        return new SupportMessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getTurn().getId(),
                message.getSequence(),
                message.getRole().name(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
