package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalResult;
import com.enjoy.agent.knowledge.application.KnowledgeRetrievalService;
import com.enjoy.agent.knowledge.application.PreparedKnowledgeBase;
import com.enjoy.agent.knowledge.domain.entity.KnowledgeBase;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeNodeExecutorVariableTest {

    @Test
    void resolvesConfiguredQueryTemplateAndKeepsUserInputAsDefault() {
        KnowledgeBaseApplicationService kbService = mock(KnowledgeBaseApplicationService.class);
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        KnowledgeBase kb = mock(KnowledgeBase.class);
        PreparedKnowledgeBase prepared = mock(PreparedKnowledgeBase.class);
        when(kbService.requireRunnableKnowledgeBase(1L, 9L)).thenReturn(kb);
        when(kbService.snapshotKnowledgeBase(kb)).thenReturn(prepared);
        when(retrievalService.retrieve(eq(prepared), any(), any(), any(), any()))
                .thenReturn(new KnowledgeRetrievalResult("context", null));
        KnowledgeNodeExecutor executor = new KnowledgeNodeExecutor(
                kbService, retrievalService, new ObjectMapper());
        WorkflowNode configured = node(
                "{\"knowledgeBaseId\":9,\"queryTemplate\":\"topic={{#draft.payload.topic#}}\"}");
        Map<String, Object> context = new HashMap<>();
        context.put("user_input", "fallback");
        context.put("node_outputs", Map.of(
                "draft", Map.of("payload", Map.of("topic", "agents"))));

        executor.execute(new NodeExecutionContext(1L, 2L, 3L, 4L, configured, null, context));

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).retrieve(eq(prepared), any(), any(), any(), question.capture());
        assertThat(question.getValue()).isEqualTo("topic=agents");

        WorkflowNode fallback = node("{\"knowledgeBaseId\":9}");
        executor.execute(new NodeExecutionContext(1L, 2L, 3L, 4L, fallback, null, context));
        verify(retrievalService).retrieve(eq(prepared), any(), any(), any(), eq("fallback"));
    }

    private WorkflowNode node(String configJson) {
        WorkflowNode node = new WorkflowNode();
        node.setId(10L);
        node.setNodeKey("knowledge");
        node.setName("knowledge");
        node.setNodeType(WorkflowNodeType.KNOWLEDGE);
        node.setConfigJson(configJson);
        return node;
    }
}
