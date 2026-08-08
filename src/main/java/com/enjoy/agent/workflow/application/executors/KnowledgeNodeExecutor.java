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

        String question = (String) ctx.context().getOrDefault("user_input", "");
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

    private record KnowledgeNodeConfig(Long knowledgeBaseId) {
    }
}
