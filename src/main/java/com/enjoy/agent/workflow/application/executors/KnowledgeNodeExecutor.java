package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalResult;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalService;
import com.enjoy.agent.knowledge.application.PreparedKnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowVariableResolver;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeNodeExecutor.class);
    private static final int MAX_RESOLVED_QUERY_LENGTH = 20_000;

    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ObjectMapper objectMapper;

    public KnowledgeNodeExecutor(
            KnowledgeBaseApplicationService knowledgeBaseApplicationService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            ObjectMapper objectMapper
    ) {
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "KNOWLEDGE";
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        KnowledgeNodeConfig config = parseConfig(ctx.node().getConfigJson());

        KnowledgeBase kb = knowledgeBaseApplicationService.requireRunnableKnowledgeBase(ctx.tenantId(), config.knowledgeBaseId);
        PreparedKnowledgeBase preparedKb = knowledgeBaseApplicationService.snapshotKnowledgeBase(kb);

        String question = resolveQuestion(ctx, config);
        List<ChatPromptMessage> history = new ArrayList<>();
        Object historyObj = ctx.context().get("history_messages");
        if (historyObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ChatPromptMessage cpm) {
                    history.add(cpm);
                }
            }
        }

        KnowledgeRetrievalResult retrievalResult = knowledgeRetrievalService.retrieve(
                preparedKb, ctx.modelConfig(), null, history, question);

        String retrievalContext = retrievalResult.retrievalContext();
        log.info("Knowledge retrieval completed: kbId={}, contextLength={}",
                config.knowledgeBaseId, retrievalContext != null ? retrievalContext.length() : 0);

        return new NodeExecutionResult(null, Map.of(
                "retrieval_context", retrievalContext != null ? retrievalContext : ""
        ));
    }

    private KnowledgeNodeConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, KnowledgeNodeConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid knowledge node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private String resolveQuestion(NodeExecutionContext ctx, KnowledgeNodeConfig config) {
        if (config.queryTemplate() != null && !config.queryTemplate().isBlank()) {
            return WorkflowVariableResolver.resolveTextTemplate(
                    config.queryTemplate(), ctx.context(), objectMapper, MAX_RESOLVED_QUERY_LENGTH,
                    this::queryTooLong);
        }
        if (config.querySelector() != null && !config.querySelector().isBlank()) {
            String selector = config.querySelector().trim();
            Object value;
            if (selector.startsWith("{{") && selector.endsWith("}}")) {
                value = WorkflowVariableResolver.resolveTypedTemplate(
                        selector, ctx.context(), objectMapper, MAX_RESOLVED_QUERY_LENGTH,
                        this::queryTooLong);
            } else {
                WorkflowVariableResolver.Resolution resolved =
                        WorkflowVariableResolver.resolveSelector(selector, ctx.context());
                value = resolved.found() ? resolved.value() : "{{" + selector + "}}";
            }
            String rendered = WorkflowVariableResolver.renderTextValue(value, objectMapper);
            if (rendered.length() > MAX_RESOLVED_QUERY_LENGTH) {
                throw queryTooLong();
            }
            return rendered;
        }
        String rendered = WorkflowVariableResolver.renderTextValue(ctx.context().get("user_input"), objectMapper);
        if (rendered.length() > MAX_RESOLVED_QUERY_LENGTH) {
            throw queryTooLong();
        }
        return rendered;
    }

    private ApiException queryTooLong() {
        return new ApiException(
                "WORKFLOW_KNOWLEDGE_QUERY_TOO_LONG",
                "Resolved workflow knowledge query must be at most "
                        + MAX_RESOLVED_QUERY_LENGTH + " characters",
                HttpStatus.BAD_REQUEST);
    }

    private record KnowledgeNodeConfig(Long knowledgeBaseId, String queryTemplate, String querySelector) {
    }
}
