package com.enjoy.agent.support.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayStreamConsumerException;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.support.api.request.SendSupportMessageRequest;
import com.enjoy.agent.support.api.response.SupportAcceptedEvent;
import com.enjoy.agent.support.api.response.SupportCompletedEvent;
import com.enjoy.agent.support.api.response.SupportConfirmationRequiredEvent;
import com.enjoy.agent.support.api.response.SupportConversationResponse;
import com.enjoy.agent.support.api.response.SupportDeltaEvent;
import com.enjoy.agent.support.api.response.SupportErrorEvent;
import com.enjoy.agent.support.api.response.SupportIntentRoutedEvent;
import com.enjoy.agent.support.api.response.SupportMessageResponse;
import com.enjoy.agent.support.api.response.SupportMessageSliceResponse;
import com.enjoy.agent.support.api.response.SupportSkillCompletedEvent;
import com.enjoy.agent.support.api.response.SupportSkillStartedEvent;
import com.enjoy.agent.support.domain.enums.SupportRouteType;
import com.enjoy.agent.support.safety.SupportHookContext;
import com.enjoy.agent.support.safety.SupportHookPhase;
import com.enjoy.agent.support.safety.SupportSafetyHookChain;
import com.enjoy.agent.support.safety.SupportTextSanitizer;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.ConfirmableSupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRegistry;
import com.enjoy.agent.support.skill.SupportSkillRequest;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 平台 AI 客服总编排器。
 *
 * <p>主线程只做认证、输入 Hook、短事务和创建 SSE；耗时的 Skill/模型工作放到
 * 虚拟线程。这样控制器可以尽快提交 text/event-stream 响应头和 accepted 事件。</p>
 */
@Service
public class SupportApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SupportApplicationService.class);
    private static final TypeReference<Map<String, Object>> TOOL_ARGUMENT_MAP = new TypeReference<>() { };

    private final SupportProperties properties;
    private final SupportConversationPersistenceService persistenceService;
    private final SupportIntentRouter intentRouter;
    private final SupportToolPlanningService toolPlanningService;
    private final SupportSkillRegistry skillRegistry;
    private final SupportSkillPayloadWriter payloadWriter;
    private final SupportSkillCallArchiveService archiveService;
    private final SupportSafetyHookChain safetyHookChain;
    private final SupportTextSanitizer sanitizer;
    private final SupportContextAssembler contextAssembler;
    private final SupportModelResolver modelResolver;
    private final ModelGatewayService modelGatewayService;
    private final SupportActionConfirmationService confirmationService;
    private final SupportConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;

    public SupportApplicationService(
            SupportProperties properties,
            SupportConversationPersistenceService persistenceService,
            SupportIntentRouter intentRouter,
            SupportToolPlanningService toolPlanningService,
            SupportSkillRegistry skillRegistry,
            SupportSkillPayloadWriter payloadWriter,
            SupportSkillCallArchiveService archiveService,
            SupportSafetyHookChain safetyHookChain,
            SupportTextSanitizer sanitizer,
            SupportContextAssembler contextAssembler,
            SupportModelResolver modelResolver,
            ModelGatewayService modelGatewayService,
            SupportActionConfirmationService confirmationService,
            SupportConversationMemoryService memoryService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.persistenceService = persistenceService;
        this.intentRouter = intentRouter;
        this.toolPlanningService = toolPlanningService;
        this.skillRegistry = skillRegistry;
        this.payloadWriter = payloadWriter;
        this.archiveService = archiveService;
        this.safetyHookChain = safetyHookChain;
        this.sanitizer = sanitizer;
        this.contextAssembler = contextAssembler;
        this.modelResolver = modelResolver;
        this.modelGatewayService = modelGatewayService;
        this.confirmationService = confirmationService;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    public SupportConversationResponse getConversation() {
        requireEnabled();
        return persistenceService.getOrCreate(CurrentUserContext.requireCurrentUser());
    }

    public SupportMessageSliceResponse listMessages(Long beforeSequence, int limit) {
        requireEnabled();
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        SupportConversationResponse conversation = persistenceService.getOrCreate(user);
        return persistenceService.listMessages(user, conversation.id(), beforeSequence, limit);
    }

    /**
     * 建立流式请求。Idempotency-Key 在 Controller 到这里的同步阶段完成校验，因此
     * 参数错误仍使用正常 HTTP 状态码；accepted 之后的运行时错误使用 SSE error。
     */
    public SseEmitter streamMessage(String idempotencyKey, SendSupportMessageRequest request) {
        requireEnabled();
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        SupportConversationResponse conversation = persistenceService.getOrCreate(user);
        SupportHookContext preTurnContext = new SupportHookContext(
                user.tenantId(), user.userId(), conversation.id(), null, user.role(), user.systemRole());
        String safeInput = safetyHookChain.inspect(
                SupportHookPhase.USER_INPUT, preTurnContext, null, request.content());
        SupportTurnStart turn = persistenceService.beginTurn(
                user, conversation.id(), idempotencyKey, safeInput);

        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<Thread> workerReference = new AtomicReference<>();
        AtomicReference<Thread> heartbeatReference = new AtomicReference<>();
        AtomicLong eventSequence = new AtomicLong();

        Runnable cancelWorker = () -> {
            if (!terminal.get()) {
                Thread worker = workerReference.get();
                if (worker != null) {
                    worker.interrupt();
                }
            }
            Thread heartbeat = heartbeatReference.get();
            if (heartbeat != null) {
                heartbeat.interrupt();
            }
        };
        emitter.onTimeout(cancelWorker);
        emitter.onError(ignored -> cancelWorker.run());
        emitter.onCompletion(cancelWorker);

        Thread worker = Thread.ofVirtual()
                .name("support-turn-" + turn.turnId())
                .start(() -> processTurn(user, turn, safeInput, emitter, terminal, eventSequence));
        workerReference.set(worker);
        Thread heartbeat = Thread.ofVirtual()
                .name("support-heartbeat-" + turn.turnId())
                .start(() -> sendHeartbeats(
                        turn.turnId(), emitter, terminal, eventSequence, workerReference));
        heartbeatReference.set(heartbeat);
        return emitter;
    }

    /**
     * 代理、负载均衡器和浏览器经常会关闭长时间无字节的 SSE。独立心跳不会等待模型
     * 首 Token；前端即使忽略 heartbeat 事件，其 idle timer 也会因收到字节而刷新。
     */
    private void sendHeartbeats(
            UUID turnId,
            SseEmitter emitter,
            AtomicBoolean terminal,
            AtomicLong eventSequence,
            AtomicReference<Thread> workerReference
    ) {
        while (!terminal.get()) {
            try {
                Thread.sleep(15_000);
                if (!terminal.get()) {
                    sendEvent(emitter, turnId, eventSequence, "heartbeat", Map.of(
                            "turnId", turnId,
                            "timestamp", Instant.now()
                    ));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | IllegalStateException ex) {
                Thread worker = workerReference.get();
                if (worker != null) {
                    worker.interrupt();
                }
                return;
            }
        }
    }

    private void processTurn(
            AuthenticatedUser user,
            SupportTurnStart turn,
            String safeInput,
            SseEmitter emitter,
            AtomicBoolean terminal,
            AtomicLong eventSequence
    ) {
        try {
            sendEvent(emitter, turn.turnId(), eventSequence, "accepted", new SupportAcceptedEvent(
                    turn.conversationId(), turn.turnId(), turn.userMessage()));

            if (turn.replay()) {
                // 幂等重放只返回已持久化结果，绝不重新执行 Skill 或调用模型。
                sendEvent(emitter, turn.turnId(), eventSequence, "delta",
                        new SupportDeltaEvent(turn.turnId(), turn.assistantMessage().content()));
                sendEvent(emitter, turn.turnId(), eventSequence, "completed",
                        new SupportCompletedEvent(turn.turnId(), turn.assistantMessage()));
                terminal.set(true);
                emitter.complete();
                return;
            }

            SupportIntentDecision decision = intentRouter.route(safeInput);
            if (decision.directAnswer() != null) {
                persistenceService.recordRouting(
                        user, turn.turnId(), turn.ownerToken(), decision.intentCode(), decision.routeType());
                sendEvent(emitter, turn.turnId(), eventSequence, "intent_routed", new SupportIntentRoutedEvent(
                        turn.turnId(), decision.intentCode(), decision.routeType().name(), decision.displayName()));
                completeRuleAnswer(user, turn, decision.directAnswer(), emitter, eventSequence);
                terminal.set(true);
                emitter.complete();
                return;
            }

            // 除固定问候和 FAQ 外，由官方客服模型进行一次原生 Tool Call 规划。规划器
            // 只返回白名单 Skill 或直接回答，不会自行执行任何工具。
            SupportPromptContext planningContext = contextAssembler.assemble(
                    user, turn.conversationId(), turn.turnId(), List.of());
            SupportToolPlan plan = toolPlanningService.plan(planningContext);
            if (plan.type() == SupportToolPlanType.DIRECT_ANSWER) {
                persistenceService.recordRouting(
                        user, turn.turnId(), turn.ownerToken(), "GENERAL_SUPPORT", SupportRouteType.LLM);
                sendEvent(emitter, turn.turnId(), eventSequence, "intent_routed", new SupportIntentRoutedEvent(
                        turn.turnId(), "GENERAL_SUPPORT", SupportRouteType.LLM.name(), "AI 意图识别"));
                completePlannedAnswer(
                        user, turn, plan.directAnswer(), plan.modelUsage(), emitter, eventSequence);
                terminal.set(true);
                emitter.complete();
                return;
            }

            persistenceService.recordRouting(
                    user, turn.turnId(), turn.ownerToken(), "LLM_TOOL_CALLS", SupportRouteType.SKILL);
            sendEvent(emitter, turn.turnId(), eventSequence, "intent_routed", new SupportIntentRoutedEvent(
                    turn.turnId(), "LLM_TOOL_CALLS", SupportRouteType.SKILL.name(), "AI 工具规划"));
            List<SupportSkillContextItem> results = executePlannedSkills(
                    user, turn, safeInput, plan.toolCalls(), emitter, eventSequence);
            completeModelAnswer(user, turn, results, plan.modelUsage(), emitter, eventSequence);
            terminal.set(true);
            emitter.complete();
        } catch (Exception exception) {
            handleTurnFailure(user, turn, emitter, terminal, eventSequence, exception);
        }
    }

    private List<SupportSkillContextItem> executePlannedSkills(
            AuthenticatedUser user,
            SupportTurnStart turn,
            String userMessage,
            List<SupportPlannedToolCall> plannedCalls,
            SseEmitter emitter,
            AtomicLong eventSequence
    ) throws IOException {
        if (plannedCalls == null || plannedCalls.isEmpty()) {
            return List.of();
        }
        List<SupportSkillContextItem> results = new ArrayList<>();
        SupportHookContext hookContext = new SupportHookContext(
                user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                user.role(), user.systemRole());

        for (SupportPlannedToolCall plannedCall : plannedCalls) {
            SupportSkill skill = skillRegistry.require(plannedCall.skillName());
            Map<String, Object> arguments = readToolArguments(plannedCall.argumentsJson());
            String requestJson = plannedCall.argumentsJson();
            String archivedRequest = safetyHookChain.inspect(
                    SupportHookPhase.BEFORE_ARCHIVE, hookContext, skill.manifest(), requestJson);
            UUID callId = archiveService.start(
                    user, turn.turnId(), turn.ownerToken(), skill.manifest(), archivedRequest);
            Instant startedAt = Instant.now();
            long startedNanos = System.nanoTime();
            boolean callArchivedAsCompleted = false;

            try {
                sendEvent(emitter, turn.turnId(), eventSequence, "skill_started", new SupportSkillStartedEvent(
                        turn.turnId(), callId, skill.manifest().name(),
                        skill.manifest().displayName(), startedAt));
                if (skill instanceof ConfirmableSupportSkill confirmableSkill) {
                    SupportActionProposal proposal = confirmationService.createProposal(
                            user, turn, confirmableSkill, arguments);
                    String checkedProposal = safetyHookChain.inspect(
                            SupportHookPhase.AFTER_SKILL_RAW,
                            hookContext,
                            skill.manifest(),
                            proposal.modelPayload()
                    );
                    String archivedProposal = safetyHookChain.inspect(
                            SupportHookPhase.BEFORE_ARCHIVE,
                            hookContext,
                            skill.manifest(),
                            checkedProposal
                    );
                    String safeSummary = "等待确认：" + proposal.title();
                    long durationMs = elapsedMillis(startedNanos);
                    archiveService.complete(user, callId, archivedProposal, safeSummary, durationMs);
                    callArchivedAsCompleted = true;
                    sendEvent(emitter, turn.turnId(), eventSequence, "skill_completed",
                            new SupportSkillCompletedEvent(
                                    turn.turnId(), callId, skill.manifest().name(),
                                    skill.manifest().displayName(), "SUCCESS", safeSummary, durationMs));
                    sendEvent(emitter, turn.turnId(), eventSequence, "confirmation_required",
                            new SupportConfirmationRequiredEvent(
                                    turn.turnId(), proposal.id(), proposal.skillName(), proposal.title(),
                                    proposal.summary(), proposal.expiresAt()));
                    results.add(new SupportSkillContextItem(skill.manifest().name(), proposal.modelPayload()));
                    continue;
                }

                safetyHookChain.inspect(
                        SupportHookPhase.BEFORE_SKILL, hookContext, skill.manifest(), requestJson);
                SupportSkillExecutionContext executionContext = new SupportSkillExecutionContext(
                        user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                        properties.getSkillPageSize());
                Long explicitId = toOptionalResourceId(arguments.get("resourceId"));
                SupportSkillRequest skillRequest = new SupportSkillRequest(
                        userMessage, explicitId, arguments);
                SupportSkillResult result = skill.execute(executionContext, skillRequest);
                String checkedRaw = safetyHookChain.inspect(
                        SupportHookPhase.AFTER_SKILL_RAW,
                        hookContext,
                        skill.manifest(),
                        result.rawPayload()
                );
                int resultLimit = Math.min(
                        properties.getMaxToolResultChars(), skill.manifest().maxModelResultChars());
                String boundedResult = payloadWriter.boundJson(checkedRaw, resultLimit);
                String modelPayload = safetyHookChain.inspect(
                        SupportHookPhase.BEFORE_MODEL_VISIBLE,
                        hookContext,
                        skill.manifest(),
                        boundedResult
                );
                String safeSummary = safetyHookChain.inspect(
                        SupportHookPhase.BEFORE_MODEL_VISIBLE,
                        hookContext,
                        skill.manifest(),
                        sanitizer.truncate(result.displaySummary(), 500)
                );
                String archivedResult = safetyHookChain.inspect(
                        SupportHookPhase.BEFORE_ARCHIVE,
                        hookContext,
                        skill.manifest(),
                        checkedRaw
                );
                long durationMs = elapsedMillis(startedNanos);
                archiveService.complete(
                        user, callId, archivedResult, safeSummary, durationMs);
                callArchivedAsCompleted = true;
                sendEvent(emitter, turn.turnId(), eventSequence, "skill_completed", new SupportSkillCompletedEvent(
                        turn.turnId(), callId, skill.manifest().name(), skill.manifest().displayName(),
                        "SUCCESS", safeSummary, durationMs));
                results.add(new SupportSkillContextItem(skill.manifest().name(), modelPayload));
            } catch (IOException | RuntimeException exception) {
                SafeFailure failure = toSafeFailure(exception);
                long durationMs = elapsedMillis(startedNanos);
                if (!callArchivedAsCompleted) {
                    try {
                        archiveService.fail(user, callId, failure.code(), failure.message(), durationMs);
                    } catch (RuntimeException archiveFailure) {
                        // 保留原始异常作为本轮失败原因；归档异常只记录类型和 ID，
                        // 绝不把请求、工具结果或底层错误文案写入日志。
                        log.error("Failed to archive support skill failure, callId={}, errorType={}",
                                callId, archiveFailure.getClass().getSimpleName());
                    }
                    try {
                        sendEvent(emitter, turn.turnId(), eventSequence, "skill_completed",
                                new SupportSkillCompletedEvent(
                                        turn.turnId(), callId, skill.manifest().name(),
                                        skill.manifest().displayName(), "FAILED",
                                        failure.message(), durationMs));
                    } catch (IOException ignored) {
                        // 当前异常本身可能就是连接断开；审计状态已经尽力落库。
                    }
                }
                throw exception;
            }
        }
        return List.copyOf(results);
    }

    private void completeRuleAnswer(
            AuthenticatedUser user,
            SupportTurnStart turn,
            String answer,
            SseEmitter emitter,
            AtomicLong eventSequence
    ) throws IOException {
        SupportHookContext hookContext = new SupportHookContext(
                user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                user.role(), user.systemRole());
        String safeAnswer = safetyHookChain.inspect(
                SupportHookPhase.AFTER_MODEL, hookContext, null, answer);
        sendEvent(emitter, turn.turnId(), eventSequence, "delta",
                new SupportDeltaEvent(turn.turnId(), safeAnswer));
        SupportMessageResponse message = persistenceService.completeTurn(
                user, turn.conversationId(), turn.turnId(), turn.ownerToken(), safeAnswer, null);
        sendEvent(emitter, turn.turnId(), eventSequence, "completed",
                new SupportCompletedEvent(turn.turnId(), message));
        memoryService.refreshSummaryAsync(user, turn.conversationId(), turn.turnId());
    }

    /** 规划模型没有选择工具时，它的普通文本就是最终答案，不再进行第二次模型调用。 */
    private void completePlannedAnswer(
            AuthenticatedUser user,
            SupportTurnStart turn,
            String answer,
            SupportModelUsage usage,
            SseEmitter emitter,
            AtomicLong eventSequence
    ) throws IOException {
        SupportHookContext hookContext = new SupportHookContext(
                user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                user.role(), user.systemRole());
        String safeAnswer = safetyHookChain.inspect(
                SupportHookPhase.AFTER_MODEL, hookContext, null, answer).trim();
        if (safeAnswer.isBlank()) {
            throw new ApiException(
                    "SUPPORT_MODEL_EMPTY_RESPONSE", "客服模型未返回有效内容", HttpStatus.BAD_GATEWAY);
        }
        sendEvent(emitter, turn.turnId(), eventSequence, "delta",
                new SupportDeltaEvent(turn.turnId(), safeAnswer));
        SupportMessageResponse message = persistenceService.completeTurn(
                user, turn.conversationId(), turn.turnId(), turn.ownerToken(), safeAnswer, usage);
        sendEvent(emitter, turn.turnId(), eventSequence, "completed",
                new SupportCompletedEvent(turn.turnId(), message));
        memoryService.refreshSummaryAsync(user, turn.conversationId(), turn.turnId());
    }

    private void completeModelAnswer(
            AuthenticatedUser user,
            SupportTurnStart turn,
            List<SupportSkillContextItem> skillResults,
            SupportModelUsage planningUsage,
            SseEmitter emitter,
            AtomicLong eventSequence
    ) {
        PreparedSupportModel supportModel = modelResolver.requireModel();
        SupportPromptContext prompt = contextAssembler.assemble(
                user, turn.conversationId(), turn.turnId(), skillResults);
        SupportHookContext hookContext = new SupportHookContext(
                user.tenantId(), user.userId(), turn.conversationId(), turn.turnId(),
                user.role(), user.systemRole());
        StringBuilder safeAnswer = new StringBuilder();

        ModelGatewayResult result = modelGatewayService.streamChatCompletion(
                supportModel.modelConfig(),
                prompt.systemPrompt(),
                prompt.sessionMemorySummary(),
                prompt.retrievalContext(),
                prompt.messages(),
                delta -> {
                    // 每个增量在写入 socket 前先过输出 Hook。由于输入和所有 Skill
                    // 结果都不含秘密，跨 chunk 凭证只是纵深防御场景，结束后还会全量复检。
                    String safeDelta = safetyHookChain.inspect(
                            SupportHookPhase.AFTER_MODEL, hookContext, null, delta);
                    if ((long) safeAnswer.length() + safeDelta.length()
                            > properties.getMaxAssistantChars()) {
                        throw new ApiException(
                                "SUPPORT_RESPONSE_TOO_LONG",
                                "客服回答超过安全长度上限，请缩小问题范围后重试",
                                HttpStatus.BAD_GATEWAY
                        );
                    }
                    safeAnswer.append(safeDelta);
                    try {
                        sendEvent(emitter, turn.turnId(), eventSequence, "delta",
                                new SupportDeltaEvent(turn.turnId(), safeDelta));
                    } catch (IOException ex) {
                        throw new ModelGatewayStreamConsumerException(
                                "Failed to deliver support streaming delta", ex);
                    }
                }
        );

        String finalAnswer = safetyHookChain.inspect(
                SupportHookPhase.AFTER_MODEL, hookContext, null, safeAnswer.toString()).trim();
        if (finalAnswer.isBlank()) {
            throw new ApiException(
                    "SUPPORT_MODEL_EMPTY_RESPONSE",
                    "客服模型未返回有效内容，请稍后重试",
                    HttpStatus.BAD_GATEWAY
            );
        }
        SupportMessageResponse message = persistenceService.completeTurn(
                user,
                turn.conversationId(),
                turn.turnId(),
                turn.ownerToken(),
                finalAnswer,
                combineUsage(planningUsage, new SupportModelUsage(
                        result.provider().name(),
                        result.modelName(),
                        supportModel.officialModelConfigId(),
                        result.latencyMs(),
                        result.promptTokens(),
                        result.completionTokens(),
                        result.totalTokens()
                ))
        );
        try {
            sendEvent(emitter, turn.turnId(), eventSequence, "completed",
                    new SupportCompletedEvent(turn.turnId(), message));
            memoryService.refreshSummaryAsync(user, turn.conversationId(), turn.turnId());
        } catch (IOException ex) {
            throw new ModelGatewayStreamConsumerException(
                    "Failed to deliver support completion event", ex);
        }
    }

    private void handleTurnFailure(
            AuthenticatedUser user,
            SupportTurnStart turn,
            SseEmitter emitter,
            AtomicBoolean terminal,
            AtomicLong eventSequence,
            Throwable throwable
    ) {
        SafeFailure failure = toSafeFailure(throwable);
        log.warn("Support turn failed, turnId={}, code={}, errorType={}",
                turn.turnId(), failure.code(), throwable.getClass().getSimpleName());
        try {
            if (!turn.replay()) {
                persistenceService.failTurn(
                        user, turn.turnId(), turn.ownerToken(), failure.code(), failure.message());
            }
        } catch (RuntimeException archiveFailure) {
            log.error("Failed to archive support turn failure, turnId={}", turn.turnId(), archiveFailure);
        }
        try {
            sendEvent(emitter, turn.turnId(), eventSequence, "error", new SupportErrorEvent(
                    turn.turnId(), failure.code(), failure.message(), failure.retryable()));
        } catch (IOException ignored) {
            // 客户端已经断开时无需再次抛错；失败状态已经尽力写入数据库。
        } finally {
            terminal.set(true);
            emitter.complete();
        }
    }

    private SafeFailure toSafeFailure(Throwable throwable) {
        if (throwable instanceof ApiException apiException) {
            return new SafeFailure(
                    apiException.getCode(),
                    sanitizer.truncate(sanitizer.redactSecrets(apiException.getMessage()), 300),
                    apiException.getStatus().is5xxServerError()
            );
        }
        if (throwable instanceof ModelGatewayInvocationException modelException) {
            return new SafeFailure(
                    modelException.getCode(),
                    "客服模型暂时不可用，请稍后重试",
                    modelException.getStatus().is5xxServerError()
            );
        }
        if (throwable instanceof ModelGatewayStreamConsumerException) {
            return new SafeFailure(
                    "SUPPORT_STREAM_DISCONNECTED",
                    "客服连接已中断，请刷新历史记录后重试",
                    true
            );
        }
        return new SafeFailure(
                "SUPPORT_INTERNAL_ERROR",
                "客服处理失败，请稍后重试",
                true
        );
    }

    private void sendEvent(
            SseEmitter emitter,
            UUID turnId,
            AtomicLong sequence,
            String eventName,
            Object data
    ) throws IOException {
        emitter.send(SseEmitter.event()
                .id(turnId + ":" + sequence.incrementAndGet())
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON));
    }

    /** 规划服务已经做过第一次 JSON 检查；执行边界再次解析并拒绝非对象参数。 */
    private Map<String, Object> readToolArguments(String argumentsJson) {
        try {
            Map<String, Object> arguments = objectMapper.readValue(argumentsJson, TOOL_ARGUMENT_MAP);
            // JSON 可选字段可能显式为 null。冻结副本可以保持执行参数不可变，同时
            // 把字段是否合法的判断留给对应 Skill，避免在协议边界泄露 NPE。
            return arguments == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        } catch (Exception ex) {
            throw new ApiException(
                    "SUPPORT_TOOL_ARGUMENTS_INVALID",
                    "客服工具参数无法解析，请重新描述需求",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    private Long toOptionalResourceId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long id = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (RuntimeException ex) {
            throw new ApiException(
                    "SUPPORT_SKILL_ARGUMENT_INVALID",
                    "资源 ID 必须是正整数",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    /** support_turn 只有一组用量列，因此保存“规划 + 最终回答”的合计值。 */
    private SupportModelUsage combineUsage(SupportModelUsage first, SupportModelUsage second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new SupportModelUsage(
                second.provider(),
                second.modelName(),
                second.officialModelConfigId(),
                sum(first.latencyMs(), second.latencyMs()),
                sum(first.promptTokens(), second.promptTokens()),
                sum(first.completionTokens(), second.completionTokens()),
                sum(first.totalTokens(), second.totalTokens())
        );
    }

    private Long sum(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private Integer sum(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
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

    private record SafeFailure(String code, String message, boolean retryable) {
    }
}
