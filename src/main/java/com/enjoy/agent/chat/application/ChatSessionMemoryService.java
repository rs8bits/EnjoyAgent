package com.enjoy.agent.chat.application;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.chat.domain.entity.ChatMessage;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.chat.domain.entity.ChatSessionMemory;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.chat.infrastructure.persistence.ChatMessageRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionMemoryRepository;
import com.enjoy.agent.chat.infrastructure.persistence.ChatSessionRepository;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 会话摘要记忆服务。
 */
@Service
public class ChatSessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionMemoryService.class);

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个会话摘要记忆整理器。
            你的任务是把已有会话摘要与新增对话整合为一份新的长期记忆摘要，供后续对话继续使用。
            输出面向模型，不是面向用户，要求忠实、简洁、结构化，不要编造不存在的新事实。
            请使用以下 Markdown 结构输出：
            - 用户目标
            - 已确认事实与约束
            - 当前进展与结论
            - 未决事项
            如果某一项暂时没有信息，可写“无”。
            """;

    private final ChatSessionMemoryRepository chatSessionMemoryRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ModelGatewayService modelGatewayService;
    private final TransactionTemplate transactionTemplate;

    public ChatSessionMemoryService(
            ChatSessionMemoryRepository chatSessionMemoryRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            ModelGatewayService modelGatewayService,
            TransactionTemplate transactionTemplate
    ) {
        this.chatSessionMemoryRepository = chatSessionMemoryRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.modelGatewayService = modelGatewayService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 读取当前会话已有的长期摘要。
     */
    public String loadSummary(Long tenantId, Long sessionId, MemoryStrategy memoryStrategy) {
        if (memoryStrategy == null) {
            return null;
        }
        return chatSessionMemoryRepository.findBySession_IdAndTenant_IdAndMemoryType(sessionId, tenantId, memoryStrategy)
                .map(ChatSessionMemory::getSummary)
                .orElse(null);
    }

    /**
     * 在一轮聊天成功后，按阈值增量刷新会话摘要。
     */
    public void refreshSummaryIfNeeded(
            Long tenantId,
            Long sessionId,
            PreparedSessionMemory preparedSessionMemory,
            PreparedModelConfig chatModelConfig
    ) {
        if (preparedSessionMemory == null
                || !preparedSessionMemory.enabled()
                || preparedSessionMemory.strategy() != MemoryStrategy.SESSION_SUMMARY
                || chatModelConfig == null) {
            return;
        }

        SummaryRefreshContext refreshContext = loadRefreshContext(tenantId, sessionId, preparedSessionMemory.strategy());
        if (refreshContext.pendingMessages().size() < resolveThreshold(preparedSessionMemory.updateMessageThreshold())) {
            return;
        }

        try {
            String summary = modelGatewayService.generateText(
                    chatModelConfig,
                    SUMMARY_SYSTEM_PROMPT,
                    buildSummaryPrompt(refreshContext.existingSummary(), refreshContext.pendingMessages())
            );
            String normalizedSummary = normalize(summary);
            if (!StringUtils.hasText(normalizedSummary)) {
                return;
            }
            Long lastMessageId = refreshContext.pendingMessages().get(refreshContext.pendingMessages().size() - 1).getId();
            persistSummary(tenantId, sessionId, preparedSessionMemory.strategy(), normalizedSummary, lastMessageId);
        } catch (ModelGatewayInvocationException ex) {
            log.warn(
                    "Session memory refresh failed, keep previous summary, sessionId={}, modelName={}, code={}",
                    sessionId,
                    chatModelConfig.modelName(),
                    ex.getCode()
            );
        } catch (RuntimeException ex) {
            log.warn("Session memory refresh failed, keep previous summary, sessionId={}", sessionId, ex);
        }
    }

    private SummaryRefreshContext loadRefreshContext(Long tenantId, Long sessionId, MemoryStrategy memoryStrategy) {
        ChatSessionMemory existingMemory = chatSessionMemoryRepository
                .findBySession_IdAndTenant_IdAndMemoryType(sessionId, tenantId, memoryStrategy)
                .orElse(null);

        List<ChatMessage> pendingMessages = existingMemory == null || existingMemory.getLastMessageId() == null
                ? chatMessageRepository.findAllBySession_IdOrderByIdAsc(sessionId)
                : chatMessageRepository.findAllBySession_IdAndIdGreaterThanOrderByIdAsc(sessionId, existingMemory.getLastMessageId());

        return new SummaryRefreshContext(
                existingMemory == null ? null : existingMemory.getSummary(),
                existingMemory == null ? null : existingMemory.getLastMessageId(),
                pendingMessages
        );
    }

    private void persistSummary(
            Long tenantId,
            Long sessionId,
            MemoryStrategy memoryStrategy,
            String summary,
            Long lastMessageId
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatSession session = chatSessionRepository.findByIdAndTenant_Id(sessionId, tenantId)
                    .orElseThrow(() -> new IllegalStateException("Chat session not found when persisting memory"));
            ChatSessionMemory memory = chatSessionMemoryRepository
                    .findBySession_IdAndTenant_IdAndMemoryType(sessionId, tenantId, memoryStrategy)
                    .orElseGet(ChatSessionMemory::new);

            if (memory.getLastMessageId() != null && lastMessageId != null && memory.getLastMessageId() >= lastMessageId) {
                return;
            }

            memory.setTenant(session.getTenant());
            memory.setSession(session);
            memory.setMemoryType(memoryStrategy);
            memory.setSummary(summary);
            memory.setLastMessageId(lastMessageId);
            memory.setVersion(memory.getId() == null ? 1 : memory.getVersion() + 1);
            chatSessionMemoryRepository.save(memory);
        });
    }

    private int resolveThreshold(Integer threshold) {
        return threshold == null || threshold <= 0 ? 6 : threshold;
    }

    private String buildSummaryPrompt(String existingSummary, List<ChatMessage> pendingMessages) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(existingSummary)) {
            builder.append("已有会话摘要：\n")
                    .append(existingSummary)
                    .append("\n\n");
        } else {
            builder.append("当前还没有会话摘要，请根据新增对话生成第一版摘要。\n\n");
        }

        builder.append("新增对话如下：\n");
        for (ChatMessage pendingMessage : pendingMessages) {
            builder.append(toRoleLabel(pendingMessage.getRole()))
                    .append(": ")
                    .append(pendingMessage.getContent())
                    .append("\n");
        }
        builder.append("\n请输出新的完整会话摘要。");
        return builder.toString();
    }

    private String toRoleLabel(ChatMessageRole role) {
        return role == ChatMessageRole.USER ? "用户" : "助手";
    }

    private String normalize(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.trim();
    }

    private record SummaryRefreshContext(
            String existingSummary,
            Long lastMessageId,
            List<ChatMessage> pendingMessages
    ) {
    }
}
