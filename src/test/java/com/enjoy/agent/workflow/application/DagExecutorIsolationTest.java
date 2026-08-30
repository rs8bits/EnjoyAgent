package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DagExecutorIsolationTest {

    private final DagExecutor dagExecutor = new DagExecutor(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void parallelNodesReceiveIsolatedSnapshotsAndPublishNamespacedOutputs() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode left = node(2L, "left", WorkflowNodeType.CONDITION);
        WorkflowNode right = node(3L, "right", WorkflowNodeType.LLM);
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END);
        List<WorkflowEdge> edges = List.of(edge(start, left), edge(start, right), edge(left, end), edge(right, end));
        AtomicBoolean rightSawMutation = new AtomicBoolean();

        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("CONDITION", ctx -> {
                    ctx.context().put("private_mutation", "must-not-leak");
                    return new NodeExecutionResult(null, Map.of("left_value", 1));
                }),
                executor("LLM", ctx -> {
                    rightSawMutation.set(ctx.context().containsKey("private_mutation"));
                    return new NodeExecutionResult(null, Map.of("right_value", 2));
                }),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of("final_output", "ok")))
        ));

        Map<String, Object> result = dagExecutor.execute(
                List.of(start, left, right, end),
                edges,
                registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of("input", "hello"))
        );

        assertThat(rightSawMutation).isFalse();
        assertThat(result).doesNotContainKey("private_mutation");
        assertThat(result).containsEntry("left_value", 1).containsEntry("right_value", 2);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodeOutputs =
                (Map<String, Map<String, Object>>) result.get("node_outputs");
        assertThat(nodeOutputs.get("left")).containsEntry("left_value", 1);
        assertThat(nodeOutputs.get("right")).containsEntry("right_value", 2);
    }

    @Test
    void parallelNodesKeepBothNamespacedOutputsAndUseStableOrderForLegacyFlatProjection() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode left = node(2L, "left", WorkflowNodeType.CONDITION);
        WorkflowNode right = node(3L, "right", WorkflowNodeType.LLM);
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END);
        List<WorkflowEdge> edges = List.of(edge(start, left), edge(start, right), edge(left, end), edge(right, end));
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("CONDITION", ctx -> new NodeExecutionResult(null, Map.of("shared", "left"))),
                executor("LLM", ctx -> new NodeExecutionResult(null, Map.of("shared", "right"))),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of()))
        ));

        Map<String, Object> result = dagExecutor.execute(
                List.of(start, left, right, end), edges, registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodeOutputs =
                (Map<String, Map<String, Object>>) result.get("node_outputs");
        assertThat(nodeOutputs.get("left")).containsEntry("shared", "left");
        assertThat(nodeOutputs.get("right")).containsEntry("shared", "right");
        // The sorted node order is left then right, so legacy flat lookup is deterministic.
        assertThat(result).containsEntry("shared", "right");
    }

    @Test
    void startSuccessorReceivesUserInputAsNodeInputWithoutPublishingNodeLocalState() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode llm = node(2L, "llm", WorkflowNodeType.LLM);
        WorkflowNode end = node(3L, "end", WorkflowNodeType.END);
        AtomicReference<Map<String, Object>> llmContext = new AtomicReference<>();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("LLM", ctx -> {
                    llmContext.set(new LinkedHashMap<>(ctx.context()));
                    return new NodeExecutionResult(null, Map.of("llm_output", "answer"));
                }),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of("final_output", "answer")))
        ));

        Map<String, Object> result = dagExecutor.execute(
                List.of(start, llm, end),
                List.of(edge(start, llm), edge(llm, end)),
                registry,
                new NodeExecutionContext(
                        1L, 2L, 3L, 4L, start, null, Map.of("user_input", "hello"))
        );

        assertThat(llmContext.get()).containsEntry("node_input", "hello");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodeInputs =
                (Map<String, Map<String, Object>>) llmContext.get().get("node_inputs");
        assertThat(nodeInputs).containsOnlyKeys("start");
        assertThat(nodeInputs.get("start")).isEmpty();
        assertThat(result).doesNotContainKeys("node_input", "node_inputs");
    }

    @Test
    void fanInRequiresExplicitInputsAndExcludesInactiveBranches() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode router = node(2L, "router", WorkflowNodeType.CONDITION);
        WorkflowNode zeta = node(3L, "zeta", WorkflowNodeType.CONDITION);
        WorkflowNode inactive = node(4L, "inactive", WorkflowNodeType.CONDITION);
        WorkflowNode alpha = node(5L, "alpha", WorkflowNodeType.CONDITION);
        WorkflowNode join = node(6L, "join", WorkflowNodeType.LLM);
        WorkflowNode end = node(7L, "end", WorkflowNodeType.END);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("items", new ArrayList<>(List.of("zeta")));
        AtomicReference<Map<String, Object>> joinContext = new AtomicReference<>();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("CONDITION", ctx -> switch (ctx.node().getNodeKey()) {
                    case "router" -> new NodeExecutionResult("true", Map.of("condition_result", true));
                    case "zeta" -> new NodeExecutionResult(null, Map.of("zeta_output", nested));
                    case "alpha" -> new NodeExecutionResult(null, Map.of("alpha_output", "alpha"));
                    default -> new NodeExecutionResult(null, Map.of("inactive_output", "must-not-flow"));
                }),
                executor("LLM", ctx -> {
                    joinContext.set(new LinkedHashMap<>(ctx.context()));
                    return new NodeExecutionResult(null, Map.of("llm_output", "joined"));
                }),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of("final_output", "joined")))
        ));
        List<WorkflowEdge> edges = List.of(
                edge(start, router),
                edge(router, alpha, "true"),
                edge(router, inactive, "false"),
                edge(router, zeta, "true"),
                edge(alpha, join),
                edge(inactive, join),
                edge(zeta, join),
                edge(join, end)
        );

        dagExecutor.execute(
                List.of(start, router, zeta, inactive, alpha, join, end),
                edges,
                registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of("user_input", "hello"))
        );

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodeInputs =
                (Map<String, Map<String, Object>>) joinContext.get().get("node_inputs");
        assertThat(nodeInputs.keySet()).containsExactly("zeta", "alpha");
        assertThat(nodeInputs).doesNotContainKey("inactive");
        assertThat(joinContext.get()).containsEntry("node_input", null);
        assertThatThrownBy(() -> nodeInputs.put("other", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> zetaInput = nodeInputs.get("zeta");
        @SuppressWarnings("unchecked")
        Map<String, Object> zetaOutput = (Map<String, Object>) zetaInput.get("zeta_output");
        assertThatThrownBy(() -> zetaOutput.put("other", true))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) zetaOutput.get("items");
        assertThatThrownBy(() -> items.add("mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sideEffectingToolNodesAtSameLevelNeverOverlap() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode first = node(2L, "tool_a", WorkflowNodeType.TOOL);
        WorkflowNode second = node(3L, "tool_b", WorkflowNodeType.TOOL);
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END);
        List<WorkflowEdge> edges = List.of(edge(start, first), edge(start, second), edge(first, end), edge(second, end));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        WorkflowNodeExecutor toolExecutor = executor("TOOL", ctx -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return new NodeExecutionResult(null, Map.of());
        });
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                toolExecutor,
                executor("END", ctx -> new NodeExecutionResult(null, Map.of()))
        ));

        dagExecutor.execute(List.of(start, first, second, end), edges, registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of()));

        assertThat(maxActive).hasValue(1);
    }

    @Test
    void failedSideEffectingNodePreventsLaterToolFromStarting() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode first = node(2L, "tool_a", WorkflowNodeType.TOOL);
        WorkflowNode second = node(3L, "tool_b", WorkflowNodeType.TOOL);
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END);
        List<WorkflowEdge> edges = List.of(edge(start, first), edge(start, second), edge(first, end), edge(second, end));
        java.util.concurrent.CopyOnWriteArrayList<String> called = new java.util.concurrent.CopyOnWriteArrayList<>();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("TOOL", ctx -> {
                    called.add(ctx.node().getNodeKey());
                    if ("tool_a".equals(ctx.node().getNodeKey())) {
                        throw new IllegalStateException("remote side effect failed");
                    }
                    return new NodeExecutionResult(null, Map.of());
                }),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of()))
        ));

        assertThatThrownBy(() -> dagExecutor.execute(List.of(start, first, second, end), edges, registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of())))
                .isInstanceOf(ApiException.class);
        assertThat(called).containsExactly("tool_a");
    }

    @Test
    void failedPaidExternalNodePreventsEveryLaterExternalCallAtTheSameLevel() {
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START);
        WorkflowNode llm = node(2L, "llm", WorkflowNodeType.LLM);
        WorkflowNode knowledge = node(3L, "knowledge", WorkflowNodeType.KNOWLEDGE);
        WorkflowNode end = node(4L, "end", WorkflowNodeType.END);
        List<WorkflowEdge> edges = List.of(
                edge(start, llm), edge(start, knowledge), edge(llm, end), edge(knowledge, end));
        java.util.concurrent.CopyOnWriteArrayList<String> called = new java.util.concurrent.CopyOnWriteArrayList<>();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                executor("START", ctx -> new NodeExecutionResult(null, Map.of())),
                executor("LLM", ctx -> {
                    called.add("llm");
                    throw new ApiException("MODEL_INVOCATION_FAILED", "failed", org.springframework.http.HttpStatus.BAD_GATEWAY);
                }),
                executor("KNOWLEDGE", ctx -> {
                    called.add("knowledge");
                    return new NodeExecutionResult(null, Map.of());
                }),
                executor("END", ctx -> new NodeExecutionResult(null, Map.of()))
        ));

        assertThatThrownBy(() -> dagExecutor.execute(
                List.of(start, llm, knowledge, end), edges, registry,
                new NodeExecutionContext(1L, 2L, 3L, 4L, start, null, Map.of())))
                .isInstanceOf(ApiException.class);
        assertThat(called).containsExactly("llm");
    }

    private WorkflowNodeExecutor executor(
            String type,
            java.util.function.Function<NodeExecutionContext, NodeExecutionResult> function
    ) {
        return new WorkflowNodeExecutor() {
            @Override
            public String nodeType() {
                return type;
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionContext context) {
                return function.apply(context);
            }
        };
    }

    private WorkflowNode node(Long id, String nodeKey, WorkflowNodeType type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setNodeKey(nodeKey);
        node.setName(nodeKey);
        node.setNodeType(type);
        node.setConfigJson("{}");
        return node;
    }

    private WorkflowEdge edge(WorkflowNode source, WorkflowNode target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        return edge;
    }

    private WorkflowEdge edge(WorkflowNode source, WorkflowNode target, String sourceHandle) {
        WorkflowEdge edge = edge(source, target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }
}
