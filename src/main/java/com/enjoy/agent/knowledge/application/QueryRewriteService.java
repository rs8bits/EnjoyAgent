package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 会话查询改写服务。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是一个知识库检索查询改写器。
            你的任务是基于最近对话，把最后一个用户问题改写为一条适合向量检索的独立查询。
            只输出改写后的查询本身，不要解释，不要回答问题，不要补充不存在的新事实。
            如果最后一个用户问题已经足够完整清晰，就直接原样输出。
            请保留原问题中的专有名词、时间、数字、限定条件和原语言。
            """;

    private final ModelGatewayService modelGatewayService;
    private final KnowledgeProperties knowledgeProperties;

    public QueryRewriteService(
            ModelGatewayService modelGatewayService,
            KnowledgeProperties knowledgeProperties
    ) {
        this.modelGatewayService = modelGatewayService;
        this.knowledgeProperties = knowledgeProperties;
    }

    /**
     * 把当前问题改写为更适合检索的独立查询。
     */
    public QueryRewriteResult rewrite(
            PreparedModelConfig chatModelConfig,
            String sessionMemorySummary,
            List<ChatPromptMessage> historyMessages,
            String latestQuestion
    ) {
        String originalQuery = normalize(latestQuestion);
        if (originalQuery == null) {
            return new QueryRewriteResult(null, null, false);
        }
        if (!knowledgeProperties.isQueryRewriteEnabled()
                || chatModelConfig == null
                || ((!StringUtils.hasText(sessionMemorySummary))
                && (historyMessages == null || historyMessages.size() <= 1))) {
            return new QueryRewriteResult(originalQuery, originalQuery, false);
        }

        try {
            String rewritten = modelGatewayService.generateText(
                    chatModelConfig,
                    REWRITE_SYSTEM_PROMPT,
                    buildRewritePrompt(sessionMemorySummary, historyMessages, originalQuery)
            );
            String normalizedRewritten = normalize(rewritten);
            if (normalizedRewritten == null) {
                return new QueryRewriteResult(originalQuery, originalQuery, false);
            }
            return new QueryRewriteResult(
                    originalQuery,
                    normalizedRewritten,
                    !originalQuery.equals(normalizedRewritten)
            );
        } catch (ModelGatewayInvocationException ex) {
            log.warn(
                    "Query rewrite failed, fallback to original query, modelName={}, code={}",
                    chatModelConfig.modelName(),
                    ex.getCode()
            );
            return new QueryRewriteResult(originalQuery, originalQuery, false);
        } catch (RuntimeException ex) {
            log.warn("Query rewrite failed, fallback to original query", ex);
            return new QueryRewriteResult(originalQuery, originalQuery, false);
        }
    }

    private String buildRewritePrompt(String sessionMemorySummary, List<ChatPromptMessage> historyMessages, String latestQuestion) {
        List<ChatPromptMessage> recentMessages = historyMessages == null
                ? List.of()
                : historyMessages.stream()
                .skip(Math.max(0, historyMessages.size() - knowledgeProperties.getQueryRewriteMaxContextMessages()))
                .toList();
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(sessionMemorySummary)) {
            builder.append("当前会话的长期摘要记忆如下：\n")
                    .append(sessionMemorySummary)
                    .append("\n\n");
        }
        builder.append("最近对话如下：\n");
        for (ChatPromptMessage message : recentMessages) {
            builder.append(message.role() == ChatMessageRole.USER ? "用户: " : "助手: ")
                    .append(message.content())
                    .append("\n");
        }
        builder.append("\n最后一个用户问题是：\n")
                .append(latestQuestion)
                .append("\n\n请输出改写后的检索查询：");
        return builder.toString();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
