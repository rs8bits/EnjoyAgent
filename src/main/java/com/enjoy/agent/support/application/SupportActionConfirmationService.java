package com.enjoy.agent.support.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.support.api.response.SupportActionExecutionResponse;
import com.enjoy.agent.support.api.response.SupportConfirmationRequiredEvent;
import com.enjoy.agent.support.api.response.SupportMessageResponse;
import com.enjoy.agent.support.domain.entity.SupportActionConfirmation;
import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportActionConfirmationStatus;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import com.enjoy.agent.support.infrastructure.persistence.SupportActionConfirmationRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportTurnRepository;
import com.enjoy.agent.support.safety.SupportHookContext;
import com.enjoy.agent.support.safety.SupportHookPhase;
import com.enjoy.agent.support.safety.SupportSafetyHookChain;
import com.enjoy.agent.support.safety.SupportTextSanitizer;
import com.enjoy.agent.support.skill.ConfirmableSupportSkill;
import com.enjoy.agent.support.skill.SupportActionPreview;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRegistry;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客服写操作的提案、确认和取消服务。
 *
 * <p>确认接口只接收 confirmationId。真实参数来自数据库中已经展示过的提案，并在
 * 执行前校验 SHA-256、当前身份、Skill 版本、权限和目标版本。删除/充值与确认状态、
 * Skill 审计和会话消息在同一个本地事务中提交，从而保证重复确认不会重复写业务数据。</p>
 */
@Service
public class SupportActionConfirmationService {

    private static final TypeReference<Map<String, Object>> ARGUMENT_MAP = new TypeReference<>() { };

    private final SupportProperties properties;
    private final SupportActionConfirmationRepository confirmationRepository;
    private final SupportTurnRepository turnRepository;
    private final SupportConversationPersistenceService persistenceService;
    private final SupportSkillRegistry skillRegistry;
    private final SupportSkillCallArchiveService archiveService;
    private final SupportSafetyHookChain safetyHookChain;
    private final SupportTextSanitizer sanitizer;
    private final SupportSkillPayloadWriter payloadWriter;
    private final ObjectMapper objectMapper;

    public SupportActionConfirmationService(
            SupportProperties properties,
            SupportActionConfirmationRepository confirmationRepository,
            SupportTurnRepository turnRepository,
            SupportConversationPersistenceService persistenceService,
            SupportSkillRegistry skillRegistry,
            SupportSkillCallArchiveService archiveService,
            SupportSafetyHookChain safetyHookChain,
            SupportTextSanitizer sanitizer,
            SupportSkillPayloadWriter payloadWriter,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.confirmationRepository = confirmationRepository;
        this.turnRepository = turnRepository;
        this.persistenceService = persistenceService;
        this.skillRegistry = skillRegistry;
        this.archiveService = archiveService;
        this.safetyHookChain = safetyHookChain;
        this.sanitizer = sanitizer;
        this.payloadWriter = payloadWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成确认卡片但绝不执行业务写入。preview 可以读取目标的安全摘要并规范化参数。
     */
    @Transactional
    public SupportActionProposal createProposal(
            AuthenticatedUser user,
            SupportTurnStart turnStart,
            ConfirmableSupportSkill skill,
            Map<String, Object> requestedArguments
    ) {
        SupportTurn sourceTurn = turnRepository
                .findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                        turnStart.turnId(), user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_TURN_NOT_FOUND", "客服请求不存在", HttpStatus.NOT_FOUND));
        if (sourceTurn.getStatus() != SupportTurnStatus.RUNNING
                || !turnStart.ownerToken().equals(sourceTurn.getOwnerToken())) {
            throw new ApiException(
                    "SUPPORT_TURN_OWNERSHIP_LOST", "客服请求执行权已失效", HttpStatus.CONFLICT);
        }

        SupportHookContext hookContext = hookContext(user, turnStart.conversationId(), turnStart.turnId());
        String requestedJson = payloadWriter.write(requestedArguments == null ? Map.of() : requestedArguments);
        safetyHookChain.inspect(SupportHookPhase.BEFORE_SKILL, hookContext, skill.manifest(), requestedJson);

        SupportSkillExecutionContext executionContext = executionContext(user, turnStart);
        SupportActionPreview preview = skill.preview(executionContext, requestedArguments);
        String normalizedJson = payloadWriter.write(preview.normalizedArguments());
        String safeArguments = safetyHookChain.inspect(
                SupportHookPhase.BEFORE_ARCHIVE, hookContext, skill.manifest(), normalizedJson);
        String argumentsHash = sha256(safeArguments);
        String safeTitle = sanitizer.truncate(safetyHookChain.inspect(
                SupportHookPhase.BEFORE_MODEL_VISIBLE, hookContext, skill.manifest(), preview.title()), 160);
        String safeSummary = sanitizer.truncate(safetyHookChain.inspect(
                SupportHookPhase.BEFORE_MODEL_VISIBLE, hookContext, skill.manifest(), preview.summary()), 1_000);

        SupportActionConfirmation confirmation = confirmationRepository
                .findBySourceTurn_IdAndSkillNameAndArgumentsHash(
                        sourceTurn.getId(), skill.manifest().name(), argumentsHash)
                .orElseGet(SupportActionConfirmation::new);
        if (confirmation.getId() == null) {
            confirmation.setId(UUID.randomUUID());
            confirmation.setConversation(sourceTurn.getConversation());
            confirmation.setSourceTurn(sourceTurn);
            confirmation.setTenant(sourceTurn.getConversation().getTenant());
            confirmation.setUser(sourceTurn.getConversation().getUser());
            confirmation.setSkillName(skill.manifest().name());
            confirmation.setSkillVersion(skill.manifest().version());
            confirmation.setArgumentsPayload(safeArguments);
            confirmation.setArgumentsHash(argumentsHash);
            confirmation.setTitle(safeTitle);
            confirmation.setOperationSummary(safeSummary);
            confirmation.setTargetUpdatedAt(preview.targetUpdatedAt());
            confirmation.setStatus(SupportActionConfirmationStatus.PENDING);
            confirmation.setExpiresAt(Instant.now().plus(
                    properties.getConfirmationTtlMinutes(), ChronoUnit.MINUTES));
            confirmationRepository.saveAndFlush(confirmation);
        }

        Map<String, Object> modelEnvelope = new LinkedHashMap<>();
        modelEnvelope.put("executed", false);
        modelEnvelope.put("confirmationRequired", true);
        modelEnvelope.put("confirmationId", confirmation.getId());
        modelEnvelope.put("title", confirmation.getTitle());
        modelEnvelope.put("summary", confirmation.getOperationSummary());
        modelEnvelope.put("expiresAt", confirmation.getExpiresAt());
        String modelPayload = safetyHookChain.inspect(
                SupportHookPhase.BEFORE_MODEL_VISIBLE,
                hookContext,
                skill.manifest(),
                payloadWriter.write(modelEnvelope)
        );
        return new SupportActionProposal(
                confirmation.getId(), confirmation.getSkillName(), confirmation.getTitle(),
                confirmation.getOperationSummary(), confirmation.getExpiresAt(), modelPayload);
    }

    /**
     * 明确确认并同步执行。该方法本身就是事务边界，业务变更、审计、消息和确认终态
     * 要么全部提交，要么全部回滚。
     */
    @Transactional
    public SupportActionExecutionResponse confirm(UUID confirmationId) {
        requireEnabled();
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        SupportActionConfirmation confirmation = requireOwnedForUpdate(user, confirmationId);
        Instant now = Instant.now();
        if (confirmation.getStatus() == SupportActionConfirmationStatus.EXECUTED) {
            return completedResponse(confirmation);
        }
        if (confirmation.getStatus() == SupportActionConfirmationStatus.CANCELLED) {
            return terminalResponse(confirmation, "该操作已经取消");
        }
        if (confirmation.getStatus() == SupportActionConfirmationStatus.EXPIRED
                || !confirmation.getExpiresAt().isAfter(now)) {
            confirmation.setStatus(SupportActionConfirmationStatus.EXPIRED);
            return terminalResponse(confirmation, "确认已经过期，请在客服中重新发起操作");
        }

        SupportSkill registeredSkill = skillRegistry.require(confirmation.getSkillName());
        if (!(registeredSkill instanceof ConfirmableSupportSkill skill)
                || !confirmation.getSkillVersion().equals(skill.manifest().version())) {
            throw new ApiException(
                    "SUPPORT_ACTION_SKILL_CHANGED",
                    "操作能力已经更新，请重新发起并确认",
                    HttpStatus.CONFLICT
            );
        }
        if (!sha256(confirmation.getArgumentsPayload()).equals(confirmation.getArgumentsHash())) {
            throw new ApiException(
                    "SUPPORT_ACTION_ARGUMENTS_CHANGED",
                    "确认参数完整性校验失败，请重新发起操作",
                    HttpStatus.CONFLICT
            );
        }

        String confirmationMessage = "确认执行：" + confirmation.getOperationSummary();
        SupportHookContext preTurnContext = hookContext(
                user, confirmation.getConversation().getId(), null);
        String safeConfirmationMessage = safetyHookChain.inspect(
                SupportHookPhase.USER_INPUT, preTurnContext, null, confirmationMessage);
        SupportTurnStart executionTurn = persistenceService.beginTurn(
                user,
                confirmation.getConversation().getId(),
                "support-confirm:" + confirmation.getId(),
                safeConfirmationMessage
        );
        if (executionTurn.replay()) {
            // 正常情况下 action 已是 EXECUTED；这条分支防御历史数据人工修复造成的
            // 状态不一致，仍然不重复执行真正的删除或充值。
            confirmation.setStatus(SupportActionConfirmationStatus.EXECUTED);
            confirmation.setExecutedAt(now);
            return new SupportActionExecutionResponse(
                    confirmation.getId(), confirmation.getStatus().name(), executionTurn.userMessage(),
                    executionTurn.assistantMessage(), "操作已经执行");
        }

        SupportTurn executionTurnEntity = turnRepository
                .findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                        executionTurn.turnId(), user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_TURN_NOT_FOUND", "客服确认请求不存在", HttpStatus.NOT_FOUND));
        confirmation.setExecutionTurn(executionTurnEntity);
        confirmation.setConfirmedAt(now);

        SupportHookContext hookContext = hookContext(
                user, confirmation.getConversation().getId(), executionTurn.turnId());
        safetyHookChain.inspect(
                SupportHookPhase.BEFORE_SKILL,
                hookContext,
                skill.manifest(),
                confirmation.getArgumentsPayload()
        );
        Map<String, Object> arguments = readArguments(confirmation.getArgumentsPayload());
        SupportActionPreview originalPreview = new SupportActionPreview(
                arguments,
                confirmation.getTitle(),
                confirmation.getOperationSummary(),
                confirmation.getTargetUpdatedAt()
        );

        UUID callId = archiveService.start(
                user,
                executionTurn.turnId(),
                executionTurn.ownerToken(),
                skill.manifest(),
                confirmation.getArgumentsPayload()
        );
        long startedNanos = System.nanoTime();
        SupportSkillResult result = skill.executeConfirmed(
                executionContext(user, executionTurn), arguments, originalPreview);
        String checkedRaw = safetyHookChain.inspect(
                SupportHookPhase.AFTER_SKILL_RAW, hookContext, skill.manifest(), result.rawPayload());
        String archivedResult = safetyHookChain.inspect(
                SupportHookPhase.BEFORE_ARCHIVE, hookContext, skill.manifest(), checkedRaw);
        String safeAnswer = sanitizer.truncate(safetyHookChain.inspect(
                SupportHookPhase.AFTER_MODEL, hookContext, skill.manifest(), result.displaySummary()),
                properties.getMaxAssistantChars());
        archiveService.complete(
                user, callId, archivedResult, safeAnswer,
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
        SupportMessageResponse assistantMessage = persistenceService.completeTurn(
                user,
                confirmation.getConversation().getId(),
                executionTurn.turnId(),
                executionTurn.ownerToken(),
                safeAnswer,
                null
        );
        confirmation.setStatus(SupportActionConfirmationStatus.EXECUTED);
        confirmation.setExecutedAt(Instant.now());
        return new SupportActionExecutionResponse(
                confirmation.getId(), confirmation.getStatus().name(), executionTurn.userMessage(),
                assistantMessage, safeAnswer);
    }

    @Transactional
    public SupportActionExecutionResponse cancel(UUID confirmationId) {
        requireEnabled();
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        SupportActionConfirmation confirmation = requireOwnedForUpdate(user, confirmationId);
        if (confirmation.getStatus() == SupportActionConfirmationStatus.PENDING) {
            if (!confirmation.getExpiresAt().isAfter(Instant.now())) {
                confirmation.setStatus(SupportActionConfirmationStatus.EXPIRED);
                return terminalResponse(confirmation, "确认已经过期");
            }
            confirmation.setStatus(SupportActionConfirmationStatus.CANCELLED);
            confirmation.setCancelledAt(Instant.now());
        }
        String message = switch (confirmation.getStatus()) {
            case EXECUTED -> "操作已经执行，无法取消";
            case EXPIRED -> "确认已经过期";
            case CANCELLED -> "已取消该操作";
            case PENDING -> "操作仍在等待确认"; // 防御未来增加状态迁移时误报“已取消”。
        };
        return terminalResponse(confirmation, message);
    }

    /**
     * 恢复当前用户刷新页面前尚未处理的确认卡片。查询同时限定 tenantId、userId、
     * PENDING 和过期时间；接口只返回安全展示字段，不返回冻结的业务参数。
     */
    @Transactional(readOnly = true)
    public List<SupportConfirmationRequiredEvent> listPending() {
        requireEnabled();
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        return confirmationRepository
                .findAllByTenant_IdAndUser_IdAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
                        user.tenantId(),
                        user.userId(),
                        SupportActionConfirmationStatus.PENDING,
                        Instant.now()
                )
                .stream()
                .map(confirmation -> new SupportConfirmationRequiredEvent(
                        confirmation.getSourceTurn().getId(),
                        confirmation.getId(),
                        confirmation.getSkillName(),
                        confirmation.getTitle(),
                        confirmation.getOperationSummary(),
                        confirmation.getExpiresAt()
                ))
                .toList();
    }

    private SupportActionConfirmation requireOwnedForUpdate(AuthenticatedUser user, UUID id) {
        return confirmationRepository.findOwnedByIdForUpdate(id, user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_CONFIRMATION_NOT_FOUND", "确认操作不存在", HttpStatus.NOT_FOUND));
    }

    private SupportActionExecutionResponse completedResponse(SupportActionConfirmation confirmation) {
        if (confirmation.getExecutionTurn() == null) {
            throw new ApiException(
                    "SUPPORT_CONFIRMATION_ARCHIVE_INCOMPLETE",
                    "确认执行记录不完整，请刷新会话历史",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        List<SupportMessageResponse> messages = persistenceService.messagesForTurn(
                CurrentUserContext.requireCurrentUser(), confirmation.getExecutionTurn().getId());
        SupportMessageResponse userMessage = messages.stream()
                .filter(message -> "USER".equals(message.role())).findFirst().orElse(null);
        SupportMessageResponse assistantMessage = messages.stream()
                .filter(message -> "ASSISTANT".equals(message.role())).findFirst().orElse(null);
        return new SupportActionExecutionResponse(
                confirmation.getId(), confirmation.getStatus().name(), userMessage, assistantMessage,
                "操作已经执行");
    }

    private SupportActionExecutionResponse terminalResponse(
            SupportActionConfirmation confirmation,
            String message
    ) {
        return new SupportActionExecutionResponse(
                confirmation.getId(), confirmation.getStatus().name(), null, null, message);
    }

    private SupportHookContext hookContext(AuthenticatedUser user, Long conversationId, UUID turnId) {
        return new SupportHookContext(
                user.tenantId(), user.userId(), conversationId, turnId, user.role(), user.systemRole());
    }

    private SupportSkillExecutionContext executionContext(
            AuthenticatedUser user,
            SupportTurnStart turn
    ) {
        return new SupportSkillExecutionContext(
                user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                properties.getSkillPageSize());
    }

    private Map<String, Object> readArguments(String payload) {
        try {
            return objectMapper.readValue(payload, ARGUMENT_MAP);
        } catch (Exception ex) {
            throw new ApiException(
                    "SUPPORT_ACTION_ARGUMENTS_INVALID",
                    "确认参数无法读取，请重新发起操作",
                    HttpStatus.CONFLICT
            );
        }
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

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ApiException(
                    "SUPPORT_DISABLED",
                    "AI 客服当前未启用",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }
}
