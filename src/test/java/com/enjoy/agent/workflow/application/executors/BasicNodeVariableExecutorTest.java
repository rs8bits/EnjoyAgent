package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BasicNodeVariableExecutorTest {

    @Test
    void startPublishesTheOriginalUserInputAsItsOwnOutput() {
        WorkflowNode start = node("start", WorkflowNodeType.START, "{}");
        Map<String, Object> input = Map.of("question", "hello");

        NodeExecutionResult result = new StartNodeExecutor().execute(
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null,
                        new HashMap<>(Map.of("user_input", input))));

        assertThat(result.output()).containsEntry("user_input", input);
    }

    @Test
    void endResolvesConfiguredNodeTemplateAndDefaultsToItsDirectInput() {
        EndNodeExecutor executor = new EndNodeExecutor(new ObjectMapper());
        WorkflowNode configured = node("end", WorkflowNodeType.END,
                "{\"outputTemplate\":\"score={{#producer.payload.score#}}\"}");
        Map<String, Object> context = new HashMap<>();
        context.put("node_outputs", Map.of(
                "producer", Map.of("payload", Map.of("score", 7))));

        assertThat(executor.execute(new NodeExecutionContext(
                1L, 2L, 3L, 4L, configured, null, context)).output())
                .containsEntry("final_output", "score=7");

        WorkflowNode fallback = node("end", WorkflowNodeType.END, "{}");
        context.put("node_input", "direct answer");
        assertThat(executor.execute(new NodeExecutionContext(
                1L, 2L, 3L, 4L, fallback, null, context)).output())
                .containsEntry("final_output", "direct answer");
    }

    private WorkflowNode node(String key, WorkflowNodeType type, String configJson) {
        WorkflowNode node = new WorkflowNode();
        node.setId(1L);
        node.setNodeKey(key);
        node.setName(key);
        node.setNodeType(type);
        node.setConfigJson(configJson);
        return node;
    }
}
