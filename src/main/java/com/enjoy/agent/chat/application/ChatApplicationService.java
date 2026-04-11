package com.enjoy.agent.chat.application;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.chat.api.request.CreateChatSessionRequest;
import com.enjoy.agent.chat.api.request.SendChatMessageRequest;
import com.enjoy.agent.chat.api.response.ChatMessageResponse;
import com.enjoy.agent.chat.api.response.ChatStreamDeltaResponse;
import com.enjoy.agent.chat.api.response.ChatStreamStartedResponse;
import com.enjoy.agent.chat.api.response.ChatSessionResponse;
import com.enjoy.agent.chat.api.response.ChatTurnResponse;
import com.enjoy.agent.chat.api.response.KnowledgeRetrievalDebugResponse;
import com.enjoy.agent.chat.api.response.RetrievedKnowledgeChunkResponse;
import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionMemoryRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalDebug;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalHit;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalResult;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalService;
import com.enjoy.agent.knowledge.application.PreparedKnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.mcp.application.McpRuntimeService;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.entity.ModelCallLog;
import com.enjoy.agent.modelgateway.application.ModelCallLogService;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayStreamConsumerException;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.modelgateway.infrastructure.persistence.ModelCallLogRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.exception.ApiError;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantStatus;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天运行时应用服务。
 */
@Service
public class ChatApplicationService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final SlidingWindowContextBuilder slidingWindowContextBuilder;
    private final ChatSessionMemoryService chatSessionMemoryService;
    private final ChatSessionMemoryRepository chatSessionMemoryRepository;
    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final McpRuntimeService mcpRuntimeService;
    private final McpChatOrchestratorService mcpChatOrchestratorService;
    private final ModelGatewayService modelGatewayService;
    private final ModelCallLogService modelCallLogService;
    private final ModelCallLogRepository modelCallLogRepository;
    private final McpToolCallLogRepository mcpToolCallLogRepository;
    private final BillingUsageApplicationService billingUsageApplicationService;
    private final BillingUsageEventRepository billingUsageEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ChatApplicationService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            AgentRepository agentRepository,
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository,
            SlidingWindowContextBuilder slidingWindowContextBuilder,
            ChatSessionMemoryService chatSessionMemoryService,
            ChatSessionMemoryRepository chatSessionMemoryRepository,
            KnowledgeBaseApplicationService knowledgeBaseApplicationService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            McpRuntimeService mcpRuntimeService,
            McpChatOrchestratorService mcpChatOrchestratorService,
            ModelGatewayService modelGatewayService,
            ModelCallLogService modelCallLogService,
            ModelCallLogRepository modelCallLogRepository,
            McpToolCallLogRepository mcpToolCallLogRepository,
            BillingUsageApplicationService billingUsageApplicationService,
            BillingUsageEventRepository billingUsageEventRepository,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.agentRepository = agentRepository;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.slidingWindowContextBuilder = slidingWindowContextBuilder;
        this.chatSessionMemoryService = chatSessionMemoryService;
        this.chatSessionMemoryRepository = chatSessionMemoryRepository;
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.mcpRuntimeService = mcpRuntimeService;
        this.mcpChatOrchestratorService = mcpChatOrchestratorService;
        this.modelGatewayService = modelGatewayService;
        this.modelCallLogService = modelCallLogService;
        this.modelCallLogRepository = modelCallLogRepository;
        this.mcpToolCallLogRepository = mcpToolCallLogRepository;
        this.billingUsageApplicationService = billingUsageApplicationService;
        this.billingUsageEventRepository = billingUsageEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 创建一个新的聊天会话。
     */
    public ChatSessionResponse createSession(CreateChatSessionRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();

        return requireTransactionResult(transactionTemplate.execute(status -> {
            Tenant tenant = requireActiveTenant(tenantId);
            AppUser user = appUserRepository.findById(currentUser.userId())
                    .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));
            Agent agent = requireRunnableAgent(tenantId, request.agentId());
            snapshotRunnableChatModelConfig(agent);

            ChatSession session = new ChatSession();
            session.setTenant(tenant);
            session.setAgent(agent);
            session.setCreatedBy(user);
            session.setTitle(resolveSessionTitle(request.title(), agent.getName()));

            ChatSession savedSession = chatSessionRepository.saveAndFlush(session);
            return toSessionResponse(savedSession);
        }));
    }

    /**
     * 查询当前租户下的会话列表。
     */
    public List<ChatSessionResponse> listSessions(Long agentId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        List<ChatSession> sessions = agentId == null
                ? chatSessionRepository.findAllByTenant_IdOrderByUpdatedAtDesc(currentUser.tenantId())
                : chatSessionRepository.findAllByTenant_IdAndAgent_IdOrderByUpdatedAtDesc(currentUser.tenantId(), agentId);

        return sessions.stream()
                .map(this::toSessionResponse)
                .toList();
    }

    /**
     * 查询会话详情。
     */
    public ChatSessionResponse getSession(Long sessionId) {
        return toSessionResponse(requireTenantOwnedSession(sessionId));
    }

    /**
     * 删除某个会话及其关联运行时数据。
     */
    public void deleteSession(Long sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatSession session = requireTenantOwnedSession(sessionId);
            Long targetSessionId = session.getId();

            billingUsageEventRepository.deleteAllBySessionId(targetSessionId);
            mcpToolCallLogRepository.deleteAllBySessionId(targetSessionId);
            chatSessionMemoryRepository.deleteAllBySession_Id(targetSessionId);
            modelCallLogRepository.deleteAllBySessionId(targetSessionId);
            chatMessageRepository.deleteAllBySession_Id(targetSessionId);
            chatSessionRepository.delete(session);
            chatSessionRepository.flush();
        });
    }

    /**
     * 查询会话消息列表。
     */
    public List<ChatMessageResponse> listMessages(Long sessionId) {
        ChatSession session = requireTenantOwnedSession(sessionId);
        return chatMessageRepository.findAllBySession_IdOrderByIdAsc(session.getId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * 发送一条用户消息，并同步得到模型回复。
     */
    public ChatTurnResponse sendMessage(Long sessionId, SendChatMessageRequest request) {
        PreparedChatTurn enrichedChatTurn = prepareEnrichedChatTurn(sessionId, request.content());
        return completePreparedChatTurn(enrichedChatTurn);
    }

    /**
     * 发送一条用户消息，并以 SSE 流式返回模型输出。
     */
    public SseEmitter streamMessage(Long sessionId, SendChatMessageRequest request) {
        PreparedChatTurn enrichedChatTurn = prepareEnrichedChatTurn(sessionId, request.content());
        boolean hasMcpTools = hasMcpTools(enrichedChatTurn);
        SseEmitter emitter = new SseEmitter(0L);

        Thread.ofVirtual()
                .name("chat-stream-" + enrichedChatTurn.sessionId())
                .start(() -> executeStreamingTurn(emitter, enrichedChatTurn, hasMcpTools));

        return emitter;
    }

    private PreparedChatTurn prepareEnrichedChatTurn(Long sessionId, String content) {
        PreparedChatTurn preparedChatTurn = requireTransactionResult(transactionTemplate.execute(status ->
                prepareChatTurn(sessionId, content)
        ));
        billingUsageApplicationService.assertUserCanUseOfficialModel(preparedChatTurn.userId(), preparedChatTurn.modelConfig());

        try {
            return enrichKnowledgeContext(preparedChatTurn);
        } catch (RuntimeException ex) {
            ModelGatewayInvocationException retrievalFailure = toKnowledgeRetrievalFailure(preparedChatTurn, ex);
            transactionTemplate.executeWithoutResult(status ->
                    modelCallLogService.recordFailure(preparedChatTurn, retrievalFailure)
            );
            throw retrievalFailure.toApiException();
        }
    }

    private ChatTurnResponse completePreparedChatTurn(PreparedChatTurn enrichedChatTurn) {
        try {
            boolean hasMcpTools = hasMcpTools(enrichedChatTurn);
            ModelGatewayResult result = hasMcpTools
                    ? mcpChatOrchestratorService.completeTurn(enrichedChatTurn)
                    : modelGatewayService.generateReply(enrichedChatTurn);
            ChatTurnResponse response = persistAssistantReplyTransactional(enrichedChatTurn, result, !hasMcpTools);
            scheduleSessionMemoryRefresh(enrichedChatTurn);
            return response;
        } catch (ModelGatewayInvocationException ex) {
            throw recordModelFailureAndConvert(enrichedChatTurn, ex);
        }
    }

    /**
     * 在事务内保存用户消息，并构造本次调用模型所需的上下文快照。
     */
    private PreparedChatTurn prepareChatTurn(Long sessionId, String content) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        ChatSession session = requireTenantOwnedSession(sessionId);
        Agent agent = requireRunnableAgent(currentUser.tenantId(), session.getAgent().getId());
        PreparedModelConfig chatModelConfig = snapshotRunnableChatModelConfig(agent);
        PreparedKnowledgeBase knowledgeBase = snapshotRunnableKnowledgeBase(agent);
        List<PreparedMcpTool> mcpTools = mcpRuntimeService.listRunnableToolsForAgent(agent.getId());
        String normalizedContent = normalizeContent(content);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setTenant(session.getTenant());
        userMessage.setSession(session);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(normalizedContent);
        ChatMessage savedUserMessage = chatMessageRepository.saveAndFlush(userMessage);

        touchSession(session);

        return new PreparedChatTurn(
                currentUser.tenantId(),
                currentUser.userId(),
                agent.getId(),
                session.getId(),
                savedUserMessage.getId(),
                normalizedContent,
                agent.getSystemPrompt(),
                snapshotSessionMemory(currentUser.tenantId(), session.getId(), agent),
                chatModelConfig,
                knowledgeBase,
                mcpTools,
                null,
                null,
                slidingWindowContextBuilder.buildWindow(session.getId(), agent.getContextWindowSize())
        );
    }

    /**
     * 在事务外补齐知识库检索上下文，避免 embedding 调用占用数据库事务。
     */
    private PreparedChatTurn enrichKnowledgeContext(PreparedChatTurn preparedChatTurn) {
        if (preparedChatTurn.knowledgeBase() == null) {
            return preparedChatTurn;
        }

        KnowledgeRetrievalResult retrievalResult = knowledgeRetrievalService.retrieve(
                preparedChatTurn.knowledgeBase(),
                preparedChatTurn.modelConfig(),
                preparedChatTurn.sessionMemory() == null ? null : preparedChatTurn.sessionMemory().summary(),
                preparedChatTurn.historyMessages(),
                preparedChatTurn.latestUserMessageContent()
        );

        return new PreparedChatTurn(
                preparedChatTurn.tenantId(),
                preparedChatTurn.userId(),
                preparedChatTurn.agentId(),
                preparedChatTurn.sessionId(),
                preparedChatTurn.userMessageId(),
                preparedChatTurn.latestUserMessageContent(),
                preparedChatTurn.systemPrompt(),
                preparedChatTurn.sessionMemory(),
                preparedChatTurn.modelConfig(),
                preparedChatTurn.knowledgeBase(),
                preparedChatTurn.mcpTools(),
                retrievalResult.retrievalContext(),
                retrievalResult.debug(),
                preparedChatTurn.historyMessages()
        );
    }

    private void executeStreamingTurn(
            SseEmitter emitter,
            PreparedChatTurn enrichedChatTurn,
            boolean hasMcpTools
    ) {
        try {
            sendStartedEvent(emitter, enrichedChatTurn, hasMcpTools);
        } catch (IOException ignored) {
            emitter.complete();
            return;
        }
        sendRetrievalEventQuietly(emitter, enrichedChatTurn.retrievalDebug());

        try {
            ChatTurnResponse response = hasMcpTools
                    ? completePreparedChatTurn(enrichedChatTurn)
                    : streamPlainPreparedChatTurn(enrichedChatTurn, emitter);
            try {
                sendEvent(emitter, "completed", response);
            } catch (IOException ignored) {
                emitter.complete();
                return;
            }
            emitter.complete();
        } catch (ModelGatewayStreamConsumerException ignored) {
            emitter.complete();
        } catch (ApiException ex) {
            sendErrorEventQuietly(emitter, ex.getCode(), ex.getMessage());
            emitter.complete();
        } catch (RuntimeException ex) {
            sendErrorEventQuietly(emitter, "INTERNAL_SERVER_ERROR", "Unexpected server error");
            emitter.completeWithError(ex);
        }
    }

    private ChatTurnResponse streamPlainPreparedChatTurn(PreparedChatTurn enrichedChatTurn, SseEmitter emitter) {
        try {
            ModelGatewayResult result = modelGatewayService.streamReply(
                    enrichedChatTurn,
                    delta -> sendDeltaEvent(emitter, delta)
            );
            ChatTurnResponse response = persistAssistantReplyTransactional(enrichedChatTurn, result, true);
            scheduleSessionMemoryRefresh(enrichedChatTurn);
            return response;
        } catch (ModelGatewayStreamConsumerException ex) {
            throw ex;
        } catch (ModelGatewayInvocationException ex) {
            throw recordModelFailureAndConvert(enrichedChatTurn, ex);
        }
    }

    /**
     * 在模型成功返回后，保存 assistant 消息并记录成功日志。
     */
    private ChatTurnResponse persistAssistantReply(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayResult result,
            boolean recordModelLog
    ) {
        ChatSession session = chatSessionRepository.findById(preparedChatTurn.sessionId())
                .orElseThrow(() -> new ApiException("CHAT_SESSION_NOT_FOUND", "Chat session not found", HttpStatus.NOT_FOUND));
        if (!Objects.equals(session.getTenant().getId(), preparedChatTurn.tenantId())) {
            throw new ApiException("CHAT_SESSION_NOT_FOUND", "Chat session not found", HttpStatus.NOT_FOUND);
        }

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setTenant(session.getTenant());
        assistantMessage.setSession(session);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent(result.content());
        ChatMessage savedAssistantMessage = chatMessageRepository.saveAndFlush(assistantMessage);

        touchSession(session);
        ModelCallLog savedModelCallLog = null;
        if (recordModelLog) {
            savedModelCallLog = modelCallLogService.recordSuccess(preparedChatTurn, result);
            billingUsageApplicationService.recordUsageEventIfNeeded(
                    preparedChatTurn,
                    result,
                    savedModelCallLog.getId()
            );
        }

        ChatMessage userMessage = chatMessageRepository.findById(preparedChatTurn.userMessageId())
                .orElseThrow(() -> new ApiException("CHAT_MESSAGE_NOT_FOUND", "User message not found", HttpStatus.INTERNAL_SERVER_ERROR));
        if (!Objects.equals(userMessage.getSession().getId(), preparedChatTurn.sessionId())) {
            throw new ApiException("CHAT_MESSAGE_NOT_FOUND", "User message not found", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ChatTurnResponse(
                session.getId(),
                toMessageResponse(userMessage),
                toMessageResponse(savedAssistantMessage),
                result.provider().name(),
                result.modelName(),
                result.credentialSource().name(),
                result.credentialId(),
                result.latencyMs(),
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                toRetrievalDebugResponse(preparedChatTurn.retrievalDebug())
        );
    }

    private ChatTurnResponse persistAssistantReplyTransactional(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayResult result,
            boolean recordModelLog
    ) {
        return requireTransactionResult(transactionTemplate.execute(status ->
                persistAssistantReply(preparedChatTurn, result, recordModelLog)
        ));
    }

    /**
     * 确保当前会话属于当前租户。
     */
    private ChatSession requireTenantOwnedSession(Long sessionId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return chatSessionRepository.findByIdAndTenant_Id(sessionId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("CHAT_SESSION_NOT_FOUND", "Chat session not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 确保当前租户存在且已启用。
     */
    private Tenant requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Current tenant not found", HttpStatus.FORBIDDEN));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException("TENANT_DISABLED", "Current tenant is disabled", HttpStatus.FORBIDDEN);
        }
        return tenant;
    }

    /**
     * 确保 Agent 属于当前租户且已经启用。
     */
    private Agent requireRunnableAgent(Long tenantId, Long agentId) {
        Agent agent = agentRepository.findByIdAndTenant_Id(agentId, tenantId)
                .orElseThrow(() -> new ApiException("AGENT_NOT_FOUND", "Agent not found", HttpStatus.NOT_FOUND));
        if (!agent.isEnabled()) {
            throw new ApiException("AGENT_DISABLED", "Agent is disabled", HttpStatus.BAD_REQUEST);
        }
        return agent;
    }

    /**
     * 确保 Agent 绑定的是一个已启用的聊天模型。
     */
    private ModelConfig requireRunnableUserChatModelConfig(ModelConfig modelConfig) {
        if (modelConfig == null) {
            throw new ApiException("MODEL_CONFIG_NOT_FOUND", "Agent user model config not found", HttpStatus.BAD_REQUEST);
        }
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Current model config is not a chat model", HttpStatus.BAD_REQUEST);
        }
        return modelConfig;
    }

    private OfficialModelConfig requireRunnableOfficialChatModelConfig(OfficialModelConfig officialModelConfig) {
        if (officialModelConfig == null) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_NOT_FOUND", "Agent official model config not found", HttpStatus.BAD_REQUEST);
        }
        if (!officialModelConfig.isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_DISABLED", "Official model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (officialModelConfig.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Current official model config is not a chat model", HttpStatus.BAD_REQUEST);
        }
        if (!officialModelConfig.getOfficialCredential().isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CREDENTIAL_DISABLED", "Official model credential is disabled", HttpStatus.BAD_REQUEST);
        }
        return officialModelConfig;
    }

    private PreparedModelConfig snapshotRunnableChatModelConfig(Agent agent) {
        AgentChatModelBindingType bindingType = agent.getChatModelBindingType() == null
                ? AgentChatModelBindingType.USER_MODEL
                : agent.getChatModelBindingType();
        if (bindingType == AgentChatModelBindingType.OFFICIAL_MODEL) {
            return snapshotOfficialModelConfig(requireRunnableOfficialChatModelConfig(agent.getOfficialModelConfig()));
        }
        return snapshotUserModelConfig(requireRunnableUserChatModelConfig(agent.getModelConfig()));
    }

    /**
     * 如果 Agent 绑定了知识库，则把它转换成运行时快照。
     */
    private PreparedKnowledgeBase snapshotRunnableKnowledgeBase(Agent agent) {
        KnowledgeBase knowledgeBase = agent.getKnowledgeBase();
        if (knowledgeBase == null) {
            return null;
        }
        ModelConfig rerankModelConfig = null;
        if (agent.isRerankEnabled()) {
            rerankModelConfig = requireRunnableRerankModelConfig(agent.getRerankModelConfig());
        }
        return knowledgeBaseApplicationService.snapshotKnowledgeBase(
                knowledgeBaseApplicationService.requireRunnableKnowledgeBase(knowledgeBase.getId()),
                agent.isRerankEnabled(),
                rerankModelConfig
        );
    }

    /**
     * 读取当前会话已有的摘要记忆，并转换成运行时快照。
     */
    private PreparedSessionMemory snapshotSessionMemory(Long tenantId, Long sessionId, Agent agent) {
        if (!agent.isMemoryEnabled()) {
            return new PreparedSessionMemory(false, null, agent.getMemoryUpdateMessageThreshold(), null);
        }
        return new PreparedSessionMemory(
                true,
                agent.getMemoryStrategy(),
                agent.getMemoryUpdateMessageThreshold(),
                chatSessionMemoryService.loadSummary(tenantId, sessionId, agent.getMemoryStrategy())
        );
    }

    /**
     * 在本轮回答成功后异步刷新会话摘要，避免阻塞当前响应。
     */
    private void scheduleSessionMemoryRefresh(PreparedChatTurn preparedChatTurn) {
        if (preparedChatTurn.sessionMemory() == null || !preparedChatTurn.sessionMemory().enabled()) {
            return;
        }
        Thread.ofVirtual()
                .name("chat-memory-" + preparedChatTurn.sessionId())
                .start(() -> chatSessionMemoryService.refreshSummaryIfNeeded(
                        preparedChatTurn.tenantId(),
                        preparedChatTurn.sessionId(),
                        preparedChatTurn.sessionMemory(),
                        preparedChatTurn.modelConfig()
                ));
    }

    /**
     * 如果 Agent 启用了 rerank，则要求绑定一个可运行的重排模型。
     */
    private ModelConfig requireRunnableRerankModelConfig(ModelConfig modelConfig) {
        if (modelConfig == null) {
            throw new ApiException("RERANK_MODEL_CONFIG_REQUIRED", "Rerank model config is required", HttpStatus.BAD_REQUEST);
        }
        if (!modelConfig.isEnabled()) {
            throw new ApiException("MODEL_CONFIG_DISABLED", "Rerank model config is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelConfig.getModelType() != ModelType.RERANK) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Current model config is not a rerank model", HttpStatus.BAD_REQUEST);
        }
        return modelConfig;
    }

    /**
     * 把知识检索阶段的失败包装成统一的模型调用异常，便于沿用现有失败日志结构。
     */
    private ModelGatewayInvocationException toKnowledgeRetrievalFailure(PreparedChatTurn preparedChatTurn, RuntimeException ex) {
        if (ex instanceof ModelGatewayInvocationException invocationException) {
            return invocationException;
        }

        PreparedModelConfig embeddingModelConfig = preparedChatTurn.knowledgeBase().embeddingModelConfig();
        ApiException apiException = ex instanceof ApiException ? (ApiException) ex : null;
        return new ModelGatewayInvocationException(
                apiException == null ? "KNOWLEDGE_RETRIEVAL_FAILED" : apiException.getCode(),
                apiException == null ? "Knowledge retrieval failed" : apiException.getMessage(),
                apiException == null ? HttpStatus.BAD_GATEWAY : apiException.getStatus(),
                embeddingModelConfig.provider(),
                embeddingModelConfig.modelName(),
                resolveCredentialSource(embeddingModelConfig),
                embeddingModelConfig.credentialId(),
                0L,
                ex
        );
    }

    /**
     * 构造运行时模型快照，避免后续逻辑依赖懒加载实体。
     */
    private PreparedModelConfig snapshotUserModelConfig(ModelConfig modelConfig) {
        return new PreparedModelConfig(
                modelConfig.getProvider(),
                modelConfig.getModelType(),
                modelConfig.getModelName(),
                CredentialSource.valueOf(resolveModelCredentialSource(modelConfig).name()),
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getId(),
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getSecretCiphertext(),
                modelConfig.getTemperature(),
                modelConfig.getMaxTokens(),
                null,
                modelConfig.getCredential() == null ? null : modelConfig.getCredential().getBaseUrl(),
                null,
                null,
                null
        );
    }

    private PreparedModelConfig snapshotOfficialModelConfig(OfficialModelConfig officialModelConfig) {
        return new PreparedModelConfig(
                officialModelConfig.getProvider(),
                officialModelConfig.getModelType(),
                officialModelConfig.getModelName(),
                CredentialSource.PLATFORM,
                officialModelConfig.getOfficialCredential().getId(),
                officialModelConfig.getOfficialCredential().getSecretCiphertext(),
                officialModelConfig.getTemperature(),
                officialModelConfig.getMaxTokens(),
                officialModelConfig.getId(),
                officialModelConfig.getOfficialCredential().getBaseUrl(),
                officialModelConfig.getInputPricePerMillion(),
                officialModelConfig.getOutputPricePerMillion(),
                officialModelConfig.getCurrency()
        );
    }

    private ModelCredentialSource resolveModelCredentialSource(ModelConfig modelConfig) {
        if (modelConfig.getCredentialSource() != null) {
            return modelConfig.getCredentialSource();
        }
        return modelConfig.getCredential() == null ? ModelCredentialSource.PLATFORM : ModelCredentialSource.USER;
    }

    /**
     * 根据运行时模型快照推断密钥来源。
     */
    private CredentialSource resolveCredentialSource(PreparedModelConfig modelConfig) {
        return modelConfig.credentialSource();
    }

    private boolean hasMcpTools(PreparedChatTurn preparedChatTurn) {
        return preparedChatTurn.mcpTools() != null && !preparedChatTurn.mcpTools().isEmpty();
    }

    private ApiException recordModelFailureAndConvert(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayInvocationException ex
    ) {
        transactionTemplate.executeWithoutResult(status ->
                modelCallLogService.recordFailure(preparedChatTurn, ex)
        );
        return ex.toApiException();
    }

    /**
     * 更新会话最后活跃时间。
     */
    private void touchSession(ChatSession session) {
        session.setUpdatedAt(Instant.now(clock));
        chatSessionRepository.save(session);
    }

    /**
     * 统一处理会话标题空白。
     */
    private String resolveSessionTitle(String title, String agentName) {
        String resolvedTitle;
        if (title == null || title.isBlank()) {
            resolvedTitle = (agentName + " 会话").trim();
        } else {
            resolvedTitle = title.trim();
        }
        return truncate(resolvedTitle, 128);
    }

    /**
     * 统一处理用户发送的消息内容。
     */
    private String normalizeContent(String content) {
        return content == null ? null : content.trim();
    }

    /**
     * 截断过长标题，避免超过数据库字段长度。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 统一兜底事务模板的空返回。
     */
    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction result must not be null");
    }

    /**
     * 转换成会话返回对象。
     */
    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getTenant().getId(),
                session.getAgent().getId(),
                session.getAgent().getName(),
                session.getTitle(),
                session.getCreatedBy().getId(),
                session.getCreatedBy().getDisplayName(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    /**
     * 转换成消息返回对象。
     */
    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSession().getId(),
                message.getRole().name(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    private void sendStartedEvent(
            SseEmitter emitter,
            PreparedChatTurn preparedChatTurn,
            boolean hasMcpTools
    ) throws IOException {
        PreparedModelConfig modelConfig = preparedChatTurn.modelConfig();
        sendEvent(
                emitter,
                "started",
                new ChatStreamStartedResponse(
                        preparedChatTurn.sessionId(),
                        preparedChatTurn.userMessageId(),
                        preparedChatTurn.latestUserMessageContent(),
                        modelConfig.provider().name(),
                        modelConfig.modelName(),
                        resolveCredentialSource(modelConfig).name(),
                        modelConfig.credentialId(),
                        hasMcpTools ? "SYNC_FALLBACK" : "STREAM"
                )
        );
    }

    private void sendDeltaEvent(SseEmitter emitter, String delta) {
        try {
            sendEvent(emitter, "delta", new ChatStreamDeltaResponse(delta));
        } catch (IOException ex) {
            throw new ModelGatewayStreamConsumerException("Failed to deliver streaming delta", ex);
        }
    }

    private void sendErrorEventQuietly(SseEmitter emitter, String code, String message) {
        try {
            sendEvent(emitter, "error", new ApiError(code, message));
        } catch (IOException ignored) {
            // Ignore client-side disconnects while trying to deliver the error event.
        }
    }

    private void sendRetrievalEventQuietly(SseEmitter emitter, KnowledgeRetrievalDebug retrievalDebug) {
        if (retrievalDebug == null) {
            return;
        }
        try {
            sendEvent(emitter, "retrieval", toRetrievalDebugResponse(retrievalDebug));
        } catch (IOException ignored) {
            // Ignore client-side disconnects while trying to deliver retrieval debug data.
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON));
    }

    private KnowledgeRetrievalDebugResponse toRetrievalDebugResponse(KnowledgeRetrievalDebug retrievalDebug) {
        if (retrievalDebug == null) {
            return null;
        }
        return new KnowledgeRetrievalDebugResponse(
                retrievalDebug.knowledgeBaseId(),
                retrievalDebug.knowledgeBaseName(),
                retrievalDebug.originalQuery(),
                retrievalDebug.retrievalQuery(),
                retrievalDebug.rewriteApplied(),
                retrievalDebug.recallTopK(),
                retrievalDebug.finalTopK(),
                retrievalDebug.rerankApplied(),
                retrievalDebug.rerankModel(),
                retrievalDebug.hits().stream()
                        .map(this::toRetrievedKnowledgeChunkResponse)
                        .toList()
        );
    }

    private RetrievedKnowledgeChunkResponse toRetrievedKnowledgeChunkResponse(KnowledgeRetrievalHit chunk) {
        return new RetrievedKnowledgeChunkResponse(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.documentName(),
                chunk.chunkIndex(),
                chunk.recallScore(),
                chunk.recallRank(),
                chunk.denseScore(),
                chunk.denseRank(),
                chunk.lexicalScore(),
                chunk.lexicalRank(),
                chunk.matchedBy(),
                chunk.rerankScore(),
                chunk.rerankRank(),
                chunk.selected(),
                chunk.content()
        );
    }
}
