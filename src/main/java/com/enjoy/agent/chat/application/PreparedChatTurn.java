package com.enjoy.agent.chat.application;

import com.enjoy.agent.knowledge.application.KnowledgeRetrievalDebug;
import com.enjoy.agent.knowledge.application.PreparedKnowledgeBase;
import com.enjoy.agent.mcp.application.PreparedMcpTool;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import java.util.List;

/**
 * 在调用模型之前整理好的聊天轮次快照。
 */
public record PreparedChatTurn(
        Long tenantId,
        Long userId,
        Long agentId,
        Long sessionId,
        Long userMessageId,
        String latestUserMessageContent,
        String systemPrompt,
        PreparedSessionMemory sessionMemory,
        PreparedModelConfig modelConfig,
        PreparedKnowledgeBase knowledgeBase,
        List<PreparedMcpTool> mcpTools,
        String retrievalContext,
        KnowledgeRetrievalDebug retrievalDebug,
        List<ChatPromptMessage> historyMessages
) {
}
