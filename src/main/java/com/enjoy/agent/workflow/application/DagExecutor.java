package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);

    public void validate(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        long startCount = nodes.stream().filter(n -> n.getNodeType() == WorkflowNodeType.START).count();
        if (startCount != 1) {
            throw new ApiException("WORKFLOW_INVALID_START", "Workflow must have exactly one START node, found " + startCount, HttpStatus.BAD_REQUEST);
        }
        long endCount = nodes.stream().filter(n -> n.getNodeType() == WorkflowNodeType.END).count();
        if (endCount != 1) {
            throw new ApiException("WORKFLOW_INVALID_END", "Workflow must have exactly one END node, found " + endCount, HttpStatus.BAD_REQUEST);
        }

        Map<Long, WorkflowNode> nodeById = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeById.put(node.getId(), node);
        }
        for (WorkflowEdge edge : edges) {
            if (!nodeById.containsKey(edge.getSourceNode().getId())) {
                throw new ApiException("WORKFLOW_INVALID_EDGE", "Edge references unknown source node", HttpStatus.BAD_REQUEST);
            }
            if (!nodeById.containsKey(edge.getTargetNode().getId())) {
                throw new ApiException("WORKFLOW_INVALID_EDGE", "Edge references unknown target node", HttpStatus.BAD_REQUEST);
            }
        }
    }

    public List<WorkflowNode> topologicalSort(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<Long, WorkflowNode> nodeById = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            nodeById.put(node.getId(), node);
        }

        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowNode node : nodes) {
            inDegree.put(node.getId(), 0);
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            Long sourceId = edge.getSourceNode().getId();
            Long targetId = edge.getTargetNode().getId();
            adjacency.get(sourceId).add(targetId);
            inDegree.merge(targetId, 1, Integer::sum);
        }

        Queue<Long> queue = new ArrayDeque<>();
        for (WorkflowNode node : nodes) {
            if (inDegree.get(node.getId()) == 0) {
                queue.add(node.getId());
            }
        }

        List<WorkflowNode> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            sorted.add(nodeById.get(currentId));
            for (Long neighborId : adjacency.get(currentId)) {
                int newDegree = inDegree.merge(neighborId, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighborId);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new ApiException("WORKFLOW_HAS_CYCLE", "Workflow contains a cycle", HttpStatus.BAD_REQUEST);
        }
        return sorted;
    }

    public record NodeEvent(String type, Long nodeId, String nodeType, String nodeName, Map<String, Object> data) {
        public static NodeEvent started(WorkflowNode node) {
            return new NodeEvent("node_started", node.getId(), node.getNodeType().name(), node.getName(), null);
        }

        public static NodeEvent completed(WorkflowNode node, Map<String, Object> output) {
            return new NodeEvent("node_completed", node.getId(), node.getNodeType().name(), node.getName(), output);
        }

        public static NodeEvent skipped(WorkflowNode node) {
            return new NodeEvent("node_skipped", node.getId(), node.getNodeType().name(), node.getName(), null);
        }

        public static NodeEvent failed(WorkflowNode node, String error) {
            return new NodeEvent("node_failed", node.getId(), node.getNodeType().name(), node.getName(), Map.of("error", error));
        }
    }

    public Map<String, Object> execute(
            List<WorkflowNode> sortedNodes,
            List<WorkflowEdge> edges,
            NodeExecutorRegistry registry,
            NodeExecutionContext baseCtx
    ) {
        return execute(sortedNodes, edges, registry, baseCtx, null);
    }

    public Map<String, Object> execute(
            List<WorkflowNode> sortedNodes,
            List<WorkflowEdge> edges,
            NodeExecutorRegistry registry,
            NodeExecutionContext baseCtx,
            Consumer<NodeEvent> eventConsumer
    ) {
        Map<String, Object> context = new ConcurrentHashMap<>(baseCtx.context());
        Map<Long, String> nodeOutputHandle = new ConcurrentHashMap<>();
        java.util.Set<Long> executed = ConcurrentHashMap.newKeySet();

        List<List<WorkflowNode>> levels = computeLevels(sortedNodes, edges);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<WorkflowNode> level : levels) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (WorkflowNode node : level) {
                    if (!shouldExecute(node, edges, executed, nodeOutputHandle)) {
                        log.info("Skipping workflow node: id={}, type={}, name={}", node.getId(), node.getNodeType().name(), node.getName());
                        if (eventConsumer != null) {
                            eventConsumer.accept(NodeEvent.skipped(node));
                        }
                        continue;
                    }

                    futures.add(CompletableFuture.runAsync(() -> {
                        String nodeType = node.getNodeType().name();
                        log.info("Executing workflow node: id={}, type={}, name={}", node.getId(), nodeType, node.getName());
                        if (eventConsumer != null) {
                            eventConsumer.accept(NodeEvent.started(node));
                        }
                        try {
                            WorkflowNodeExecutor nodeExecutor = registry.get(nodeType);
                            NodeExecutionContext nodeCtx = new NodeExecutionContext(
                                    baseCtx.tenantId(), baseCtx.userId(), baseCtx.agentId(), baseCtx.sessionId(),
                                    node, baseCtx.modelConfig(), context
                            );
                            NodeExecutionResult result = nodeExecutor.execute(nodeCtx);
                            if (result.output() != null) {
                                context.putAll(result.output());
                            }
                            executed.add(node.getId());
                            if (result.nextHandle() != null) {
                                nodeOutputHandle.put(node.getId(), result.nextHandle());
                            }
                            if (eventConsumer != null) {
                                eventConsumer.accept(NodeEvent.completed(node, result.output()));
                            }
                        } catch (RuntimeException ex) {
                            log.warn("Workflow node failed: id={}, type={}", node.getId(), nodeType, ex);
                            if (eventConsumer != null) {
                                eventConsumer.accept(NodeEvent.failed(node, ex.getMessage()));
                            }
                            throw ex;
                        }
                        log.info("Completed workflow node: id={}, type={}", node.getId(), nodeType);
                    }, executor));
                }

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                } catch (Exception ex) {
                    throw new ApiException("WORKFLOW_EXECUTION_FAILED",
                            "Workflow execution failed: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        return context;
    }

    private List<List<WorkflowNode>> computeLevels(List<WorkflowNode> sortedNodes, List<WorkflowEdge> edges) {
        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowNode node : sortedNodes) {
            inDegree.put(node.getId(), 0);
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            adjacency.get(edge.getSourceNode().getId()).add(edge.getTargetNode().getId());
            inDegree.merge(edge.getTargetNode().getId(), 1, Integer::sum);
        }

        Map<Long, WorkflowNode> nodeById = new LinkedHashMap<>();
        for (WorkflowNode node : sortedNodes) {
            nodeById.put(node.getId(), node);
        }

        List<List<WorkflowNode>> levels = new ArrayList<>();
        Queue<Long> currentLevel = new ArrayDeque<>();
        for (WorkflowNode node : sortedNodes) {
            if (inDegree.get(node.getId()) == 0) {
                currentLevel.add(node.getId());
            }
        }

        while (!currentLevel.isEmpty()) {
            List<WorkflowNode> levelNodes = new ArrayList<>();
            Queue<Long> nextLevel = new ArrayDeque<>();

            while (!currentLevel.isEmpty()) {
                Long nodeId = currentLevel.poll();
                levelNodes.add(nodeById.get(nodeId));
                for (Long neighborId : adjacency.get(nodeId)) {
                    int newDegree = inDegree.merge(neighborId, -1, Integer::sum);
                    if (newDegree == 0) {
                        nextLevel.add(neighborId);
                    }
                }
            }
            levels.add(levelNodes);
            currentLevel = nextLevel;
        }

        return levels;
    }

    private boolean shouldExecute(
            WorkflowNode node,
            List<WorkflowEdge> edges,
            java.util.Set<Long> executed,
            Map<Long, String> nodeOutputHandle
    ) {
        if (node.getNodeType() == WorkflowNodeType.START) {
            return true;
        }

        List<WorkflowEdge> incoming = edges.stream()
                .filter(e -> e.getTargetNode().getId().equals(node.getId()))
                .toList();

        if (incoming.isEmpty()) {
            return false;
        }

        for (WorkflowEdge edge : incoming) {
            Long sourceId = edge.getSourceNode().getId();
            if (!executed.contains(sourceId)) {
                continue;
            }
            String sourceHandle = nodeOutputHandle.get(sourceId);
            String edgeSourceHandle = edge.getSourceHandle();
            if (sourceHandle == null || sourceHandle.equals(edgeSourceHandle)) {
                return true;
            }
        }
        return false;
    }
}
