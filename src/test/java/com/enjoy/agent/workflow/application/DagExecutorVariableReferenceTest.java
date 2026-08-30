package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DagExecutorVariableReferenceTest {

    private final DagExecutor executor = new DagExecutor(new ObjectMapper());

    @Test
    void acceptsCanonicalAndDifyReferencesToReachableAncestors() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, "{}");
        WorkflowNode producer = node(2L, "draft", WorkflowNodeType.LLM, "{}");
        WorkflowNode consumer = node(3L, "review", WorkflowNodeType.LLM,
                "{\"systemPrompt\":\"{{node_outputs.draft.llm_output}} / {{#start.user_input#}}\"}");
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END, "{}");

        assertThatCode(() -> executor.validate(
                List.of(start, producer, consumer, end),
                List.of(edge(start, producer), edge(producer, consumer), edge(consumer, end))))
                .doesNotThrowAnyException();

        consumer.setNodeType(WorkflowNodeType.KNOWLEDGE);
        consumer.setConfigJson("{\"knowledgeBaseId\":9,\"querySelector\":\"draft.llm_output\"}");
        assertThatCode(() -> executor.validate(
                List.of(start, producer, consumer, end),
                List.of(edge(start, producer), edge(producer, consumer), edge(consumer, end))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingNodesUnknownOutputFieldsAndParallelBranchReads() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, "{}");
        WorkflowNode left = node(2L, "left", WorkflowNodeType.LLM, "{}");
        WorkflowNode right = node(3L, "right", WorkflowNodeType.LLM,
                "{\"systemPrompt\":\"{{#left.llm_output#}}\"}");
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END, "{}");
        List<WorkflowEdge> parallel = List.of(
                edge(start, left), edge(start, right), edge(left, end), edge(right, end));

        assertCode(() -> executor.validate(List.of(start, left, right, end), parallel),
                "WORKFLOW_VARIABLE_SCOPE_INVALID");

        right.setConfigJson("{\"systemPrompt\":\"{{node_outputs.missing.llm_output}}\"}");
        assertCode(() -> executor.validate(List.of(start, left, right, end), parallel),
                "WORKFLOW_VARIABLE_REFERENCE_INVALID");

        right.setConfigJson("{\"systemPrompt\":\"{{node_outputs.start.llm_output}}\"}");
        assertCode(() -> executor.validate(List.of(start, left, right, end), parallel),
                "WORKFLOW_VARIABLE_REFERENCE_INVALID");
    }

    @Test
    void rejectsLegacyNodeInputWhenTheConsumerHasMultipleBusinessPredecessors() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, "{}");
        WorkflowNode left = node(2L, "left", WorkflowNodeType.LLM, "{}");
        WorkflowNode right = node(3L, "right", WorkflowNodeType.LLM, "{}");
        WorkflowNode join = node(4L, "join", WorkflowNodeType.LLM,
                "{\"systemPrompt\":\"{{node_input}}\"}");
        WorkflowNode end = node(5L, "end", WorkflowNodeType.END, "{}");

        assertCode(() -> executor.validate(
                        List.of(start, left, right, join, end),
                        List.of(edge(start, left), edge(start, right),
                                edge(left, join), edge(right, join), edge(join, end))),
                "AMBIGUOUS_NODE_INPUT");
    }

    @Test
    void loopBodyOutputsArePrivateOutsideTheLoopAndExposedThroughTheLoopSchema() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, "{}");
        WorkflowNode body = node(2L, "body", WorkflowNodeType.LLM, "{}");
        WorkflowNode loop = node(3L, "loop", WorkflowNodeType.LOOP,
                "{\"bodyNodeKeys\":[\"body\"],\"maxIterations\":1}");
        WorkflowNode consumer = node(4L, "consumer", WorkflowNodeType.LLM,
                "{\"systemPrompt\":\"{{body.llm_output}}\"}");
        WorkflowNode end = node(5L, "end", WorkflowNodeType.END, "{}");
        List<WorkflowNode> nodes = List.of(start, body, loop, consumer, end);
        List<WorkflowEdge> edges = List.of(edge(start, loop), edge(loop, consumer), edge(consumer, end));

        assertCode(() -> executor.validate(nodes, edges), "WORKFLOW_VARIABLE_SCOPE_INVALID");

        consumer.setConfigJson(
                "{\"systemPrompt\":\"{{loop.body_outputs.body.llm_output}}\"}");
        assertThatCode(() -> executor.validate(nodes, edges)).doesNotThrowAnyException();
    }

    @Test
    void firstLoopBodyCannotUseNodeInputWhenTheLoopHasMultipleBusinessPredecessors() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, "{}");
        WorkflowNode left = node(2L, "left", WorkflowNodeType.LLM, "{}");
        WorkflowNode right = node(3L, "right", WorkflowNodeType.LLM, "{}");
        WorkflowNode body = node(4L, "body", WorkflowNodeType.LLM,
                "{\"systemPrompt\":\"{{node_input}}\"}");
        WorkflowNode loop = node(5L, "loop", WorkflowNodeType.LOOP,
                "{\"bodyNodeKeys\":[\"body\"],\"maxIterations\":1}");
        WorkflowNode end = node(6L, "end", WorkflowNodeType.END, "{}");

        assertCode(() -> executor.validate(
                        List.of(start, left, right, body, loop, end),
                        List.of(edge(start, left), edge(start, right),
                                edge(left, loop), edge(right, loop), edge(loop, end))),
                "AMBIGUOUS_NODE_INPUT");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private WorkflowNode node(Long id, String key, WorkflowNodeType type, String config) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setNodeKey(key);
        node.setName(key);
        node.setNodeType(type);
        node.setConfigJson(config);
        return node;
    }

    private WorkflowEdge edge(WorkflowNode source, WorkflowNode target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        return edge;
    }
}
