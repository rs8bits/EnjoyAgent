package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.NodeExecutorRegistry;
import com.enjoy.agent.workflow.application.WorkflowConditionEvaluator;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoopNodeExecutorTest {

    @Mock
    private WorkflowNodeRepository repository;

    @Mock
    private NodeExecutorRegistry registry;

    @Mock
    private WorkflowNodeExecutor bodyExecutor;

    @Test
    void resolvesStableKeysOnlyWithinExecutingWorkflow() {
        Workflow workflow = workflow(21L, 7L);
        WorkflowNode loop = node(30L, "loop", WorkflowNodeType.LOOP, workflow,
                "{\"bodyNodeKeys\":[\"body\"],\"maxIterations\":3,"
                        + "\"exitCondition\":\"{{#body.condition_result#}} == true\"}");
        WorkflowNode body = node(31L, "body", WorkflowNodeType.LLM, workflow, "{}");
        when(repository.findAllByWorkflow_IdAndNodeKeyIn(21L, List.of("body")))
                .thenReturn(List.of(body));
        when(registry.get("LLM")).thenReturn(bodyExecutor);
        when(bodyExecutor.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NodeExecutionResult(null, Map.of("condition_result", true)));
        LoopNodeExecutor executor = new LoopNodeExecutor(
                repository, registry, new ObjectMapper(), new WorkflowConditionEvaluator(new ObjectMapper()));

        NodeExecutionResult result = executor.execute(new NodeExecutionContext(
                7L, 8L, 9L, 10L, loop, null, new HashMap<>()));

        verify(repository).findAllByWorkflow_IdAndNodeKeyIn(21L, List.of("body"));
        assertThat(result.output()).containsEntry("loop_iterations", 1)
                .containsEntry("loop_break_reason", "exit_condition_met");
    }

    @Test
    void rejectsUnboundedIterationsBeforeLoadingBody() {
        Workflow workflow = workflow(21L, 7L);
        WorkflowNode loop = node(30L, "loop", WorkflowNodeType.LOOP, workflow,
                "{\"bodyNodeKeys\":[\"body\"],\"maxIterations\":101}");
        LoopNodeExecutor executor = new LoopNodeExecutor(
                repository, registry, new ObjectMapper(), new WorkflowConditionEvaluator(new ObjectMapper()));

        assertThatThrownBy(() -> executor.execute(new NodeExecutionContext(
                7L, 8L, 9L, 10L, loop, null, new HashMap<>())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("LOOP_ITERATION_LIMIT_INVALID"));
    }

    @Test
    void publishesBodyNamespacesAndFeedsEachBodyFromThePreviousOutput() {
        Workflow workflow = workflow(21L, 7L);
        WorkflowNode loop = node(30L, "loop", WorkflowNodeType.LOOP, workflow,
                "{\"bodyNodeKeys\":[\"first\",\"second\"],\"maxIterations\":1}");
        WorkflowNode first = node(31L, "first", WorkflowNodeType.LLM, workflow, "{}");
        WorkflowNode second = node(32L, "second", WorkflowNodeType.CONDITION, workflow, "{}");
        when(repository.findAllByWorkflow_IdAndNodeKeyIn(21L, List.of("first", "second")))
                .thenReturn(List.of(first, second));
        when(registry.get("LLM")).thenReturn(bodyExecutor);
        when(registry.get("CONDITION")).thenReturn(bodyExecutor);
        List<Map<String, Object>> contexts = new ArrayList<>();
        when(bodyExecutor.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            NodeExecutionContext bodyCtx = invocation.getArgument(0);
            contexts.add(new LinkedHashMap<>(bodyCtx.context()));
            return "first".equals(bodyCtx.node().getNodeKey())
                    ? new NodeExecutionResult(null, Map.of("llm_output", Map.of("score", 7)))
                    : new NodeExecutionResult(null, Map.of("condition_result", true));
        });
        LoopNodeExecutor executor = new LoopNodeExecutor(
                repository, registry, new ObjectMapper(), new WorkflowConditionEvaluator(new ObjectMapper()));
        Map<String, Object> context = new HashMap<>();
        context.put("node_input", "incoming");
        context.put("node_inputs", Map.of("upstream", Map.of("value", "incoming")));
        context.put("node_outputs", Map.of("upstream", Map.of("value", "incoming")));

        NodeExecutionResult result = executor.execute(new NodeExecutionContext(
                7L, 8L, 9L, 10L, loop, null, context));

        assertThat(contexts.get(0)).containsEntry("node_input", "incoming");
        assertThat(contexts.get(1).get("node_input")).isEqualTo(Map.of("score", 7));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> secondInputs =
                (Map<String, Map<String, Object>>) contexts.get(1).get("node_inputs");
        assertThat(secondInputs).containsOnlyKeys("first");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> namespace =
                (Map<String, Map<String, Object>>) contexts.get(1).get("node_outputs");
        assertThat(namespace.get("upstream")).containsEntry("value", "incoming");
        assertThat(namespace.get("first")).containsEntry("llm_output", Map.of("score", 7));
        assertThat(result.childNodeOutputs()).containsKeys("first", "second");
        assertThat(result.output()).containsKey("body_outputs");
    }

    private Workflow workflow(Long workflowId, Long tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        Workflow workflow = new Workflow();
        workflow.setId(workflowId);
        workflow.setTenant(tenant);
        return workflow;
    }

    private WorkflowNode node(
            Long id,
            String nodeKey,
            WorkflowNodeType type,
            Workflow workflow,
            String config
    ) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setNodeKey(nodeKey);
        node.setName(nodeKey);
        node.setNodeType(type);
        node.setWorkflow(workflow);
        node.setConfigJson(config);
        return node;
    }
}
