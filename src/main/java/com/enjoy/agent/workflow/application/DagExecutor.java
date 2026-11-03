package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

@Component
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 500;
    private final ObjectMapper objectMapper;

    public DagExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
            throw new ApiException("WORKFLOW_GRAPH_LIMIT_EXCEEDED",
                    "Workflow graph exceeds the maximum of " + MAX_NODES + " nodes or " + MAX_EDGES + " edges",
                    HttpStatus.BAD_REQUEST);
        }
        java.util.Set<String> nodeKeys = new java.util.HashSet<>();
        for (WorkflowNode node : nodes) {
            if (node.getNodeKey() == null || node.getNodeKey().isBlank()
                    || WorkflowVariableResolver.isReservedNodeKey(node.getNodeKey())
                    || !nodeKeys.add(node.getNodeKey())) {
                throw new ApiException("WORKFLOW_NODE_KEY_INVALID",
                        "Workflow nodeKey values must be present, unique, and not reserved", HttpStatus.BAD_REQUEST);
            }
        }
        long startCount = nodes.stream().filter(n -> n.getNodeType() == WorkflowNodeType.START).count();
        if (startCount != 1) {
            throw new ApiException("WORKFLOW_INVALID_START", "Workflow must have exactly one START node, found " + startCount, HttpStatus.BAD_REQUEST);
        }
        long endCount = nodes.stream().filter(n -> n.getNodeType() == WorkflowNodeType.END).count();
        if (endCount != 1) {
            throw new ApiException("WORKFLOW_INVALID_END", "Workflow must have exactly one END node, found " + endCount, HttpStatus.BAD_REQUEST);
        }

        Map<Long, WorkflowNode> nodeById = new HashMap<>();
        Map<String, WorkflowNode> nodeByKey = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeById.put(node.getId(), node);
            nodeByKey.put(node.getNodeKey(), node);
        }
        Set<Long> nestedBodyIds = resolveNestedLoopBodyIds(nodes, nodeById, nodeByKey);
        Set<String> uniqueEdges = new java.util.HashSet<>();
        for (WorkflowEdge edge : edges) {
            if (!nodeById.containsKey(edge.getSourceNode().getId())) {
                throw new ApiException("WORKFLOW_INVALID_EDGE", "Edge references unknown source node", HttpStatus.BAD_REQUEST);
            }
            if (!nodeById.containsKey(edge.getTargetNode().getId())) {
                throw new ApiException("WORKFLOW_INVALID_EDGE", "Edge references unknown target node", HttpStatus.BAD_REQUEST);
            }
            Long sourceId = edge.getSourceNode().getId();
            Long targetId = edge.getTargetNode().getId();
            if (sourceId.equals(targetId)) {
                throw new ApiException("WORKFLOW_SELF_EDGE", "Workflow edges cannot point to the same node", HttpStatus.BAD_REQUEST);
            }
            String edgeIdentity = sourceId + "|" + targetId + "|"
                    + String.valueOf(edge.getSourceHandle()) + "|" + String.valueOf(edge.getTargetHandle());
            if (!uniqueEdges.add(edgeIdentity)) {
                throw new ApiException("WORKFLOW_EDGE_DUPLICATED", "Workflow contains a duplicated edge", HttpStatus.BAD_REQUEST);
            }
            if (nestedBodyIds.contains(sourceId) || nestedBodyIds.contains(targetId)) {
                throw new ApiException("LOOP_BODY_EDGE_INVALID",
                        "Loop body nodes cannot also participate in top-level workflow edges", HttpStatus.BAD_REQUEST);
            }
        }
        validateTopLevelReachability(nodes, edges, nestedBodyIds);
        validateVariableReferences(nodes, edges, nodeById, nodeByKey, nestedBodyIds);
    }

    private Set<Long> resolveNestedLoopBodyIds(
            List<WorkflowNode> nodes,
            Map<Long, WorkflowNode> nodeById,
            Map<String, WorkflowNode> nodeByKey
    ) {
        Set<Long> nested = new java.util.HashSet<>();
        Map<Long, Long> ownerByBodyId = new HashMap<>();
        for (WorkflowNode loop : nodes) {
            if (loop.getNodeType() != WorkflowNodeType.LOOP) {
                continue;
            }
            final JsonNode config;
            try {
                config = objectMapper.readTree(loop.getConfigJson());
            } catch (Exception ex) {
                throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID", "Invalid loop config", HttpStatus.BAD_REQUEST);
            }
            List<WorkflowNode> bodyNodes = new ArrayList<>();
            JsonNode keys = config.path("bodyNodeKeys");
            JsonNode ids = config.path("bodyNodeIds");
            if (keys.isArray() && !keys.isEmpty()) {
                for (JsonNode key : keys) {
                    WorkflowNode body = key.isTextual() ? nodeByKey.get(key.asText()) : null;
                    if (body == null) {
                        throw new ApiException("LOOP_BODY_NOT_FOUND",
                                "Loop bodyNodeKeys must reference nodes in the same workflow", HttpStatus.BAD_REQUEST);
                    }
                    bodyNodes.add(body);
                }
            } else if (ids.isArray() && !ids.isEmpty()) {
                for (JsonNode id : ids) {
                    WorkflowNode body = id.canConvertToLong() ? nodeById.get(id.asLong()) : null;
                    if (body == null) {
                        throw new ApiException("LOOP_BODY_NOT_FOUND",
                                "Legacy loop bodyNodeIds must reference nodes in the same workflow", HttpStatus.BAD_REQUEST);
                    }
                    bodyNodes.add(body);
                }
            } else {
                throw new ApiException("LOOP_EMPTY_BODY", "Loop node must have at least one body node", HttpStatus.BAD_REQUEST);
            }
            Set<Long> local = new java.util.HashSet<>();
            for (WorkflowNode body : bodyNodes) {
                if (!local.add(body.getId())
                        || body.getId().equals(loop.getId())
                        || body.getNodeType() == WorkflowNodeType.START
                        || body.getNodeType() == WorkflowNodeType.END
                        || body.getNodeType() == WorkflowNodeType.LOOP) {
                    throw new ApiException("LOOP_BODY_INVALID",
                            "Loop bodies must be unique non-START/END/LOOP nodes", HttpStatus.BAD_REQUEST);
                }
                Long previousOwner = ownerByBodyId.putIfAbsent(body.getId(), loop.getId());
                if (previousOwner != null) {
                    throw new ApiException("LOOP_BODY_REUSED",
                            "A loop body node cannot belong to multiple loops", HttpStatus.BAD_REQUEST);
                }
                nested.add(body.getId());
            }
        }
        return nested;
    }

    private void validateTopLevelReachability(
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            Set<Long> nestedBodyIds
    ) {
        List<WorkflowNode> topLevel = nodes.stream()
                .filter(node -> !nestedBodyIds.contains(node.getId())).toList();
        WorkflowNode start = topLevel.stream().filter(node -> node.getNodeType() == WorkflowNodeType.START)
                .findFirst().orElseThrow();
        WorkflowNode end = topLevel.stream().filter(node -> node.getNodeType() == WorkflowNodeType.END)
                .findFirst().orElseThrow();
        Map<Long, List<Long>> forward = new HashMap<>();
        Map<Long, List<Long>> reverse = new HashMap<>();
        for (WorkflowNode node : topLevel) {
            forward.put(node.getId(), new ArrayList<>());
            reverse.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            forward.get(edge.getSourceNode().getId()).add(edge.getTargetNode().getId());
            reverse.get(edge.getTargetNode().getId()).add(edge.getSourceNode().getId());
        }
        Set<Long> fromStart = reachable(start.getId(), forward);
        Set<Long> toEnd = reachable(end.getId(), reverse);
        Set<Long> expected = topLevel.stream().map(WorkflowNode::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!fromStart.containsAll(expected) || !toEnd.containsAll(expected)) {
            throw new ApiException("WORKFLOW_GRAPH_DISCONNECTED",
                    "Every top-level workflow node must be reachable from START and able to reach END",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private Set<Long> reachable(Long origin, Map<Long, List<Long>> adjacency) {
        Set<Long> visited = new java.util.HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(origin);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (visited.add(current)) {
                queue.addAll(adjacency.getOrDefault(current, List.of()));
            }
        }
        return visited;
    }

    private void validateVariableReferences(
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            Map<Long, WorkflowNode> nodeById,
            Map<String, WorkflowNode> nodeByKey,
            Set<Long> nestedBodyIds
    ) {
        Map<Long, List<WorkflowNode>> loopBodies = resolveLoopBodiesForReferences(nodes, nodeById, nodeByKey);
        Map<Long, Long> ownerByBodyId = new HashMap<>();
        for (Map.Entry<Long, List<WorkflowNode>> entry : loopBodies.entrySet()) {
            for (WorkflowNode body : entry.getValue()) {
                ownerByBodyId.put(body.getId(), entry.getKey());
            }
        }

        Map<Long, List<Long>> reverse = new HashMap<>();
        for (WorkflowNode node : nodes) {
            if (!nestedBodyIds.contains(node.getId())) {
                reverse.put(node.getId(), new ArrayList<>());
            }
        }
        for (WorkflowEdge edge : edges) {
            reverse.computeIfAbsent(edge.getTargetNode().getId(), ignored -> new ArrayList<>())
                    .add(edge.getSourceNode().getId());
        }

        for (WorkflowNode consumer : nodes) {
            ConfigVariables configVariables = readConfigVariables(consumer);
            Long ownerLoopId = ownerByBodyId.get(consumer.getId());
            Long inputOwnerId = consumer.getId();
            if (ownerLoopId != null) {
                List<WorkflowNode> body = loopBodies.getOrDefault(ownerLoopId, List.of());
                if (!body.isEmpty() && body.getFirst().getId().equals(consumer.getId())) {
                    // The first body node inherits the Loop node's direct inputs.
                    inputOwnerId = ownerLoopId;
                }
            }
            Long directInputOwnerId = inputOwnerId;
            long directBusinessPredecessors = edges.stream()
                    .filter(edge -> edge.getTargetNode().getId().equals(directInputOwnerId))
                    .map(WorkflowEdge::getSourceNode)
                    .filter(source -> source.getNodeType() != WorkflowNodeType.START)
                    .map(WorkflowNode::getId)
                    .distinct()
                    .count();
            if (directBusinessPredecessors > 1
                    && configVariables.usesNodeInput()) {
                throw new ApiException(
                        "AMBIGUOUS_NODE_INPUT",
                        "node_input is ambiguous with multiple direct predecessors; use nodeKey.field instead",
                        HttpStatus.BAD_REQUEST);
            }
            Set<Long> allowed = new java.util.HashSet<>();
            if (ownerLoopId != null) {
                allowed.addAll(reachable(ownerLoopId, reverse));
                allowed.remove(ownerLoopId);
                List<WorkflowNode> body = loopBodies.getOrDefault(ownerLoopId, List.of());
                for (WorkflowNode bodyNode : body) {
                    if (bodyNode.getId().equals(consumer.getId())) {
                        break;
                    }
                    allowed.add(bodyNode.getId());
                }
            } else {
                allowed.addAll(reachable(consumer.getId(), reverse));
                allowed.remove(consumer.getId());
                if (consumer.getNodeType() == WorkflowNodeType.LOOP) {
                    loopBodies.getOrDefault(consumer.getId(), List.of())
                            .forEach(body -> allowed.add(body.getId()));
                }
            }
            for (WorkflowVariableResolver.NodeReference reference : configVariables.references()) {
                WorkflowNode source = nodeByKey.get(reference.nodeKey());
                if (source == null) {
                    throw new ApiException(
                            "WORKFLOW_VARIABLE_REFERENCE_INVALID",
                            "Workflow variable references an unknown nodeKey: " + reference.nodeKey(),
                            HttpStatus.BAD_REQUEST);
                }
                String field = reference.fieldPath().isEmpty() ? "" : reference.fieldPath().getFirst();
                if (!WorkflowVariableResolver.outputFields(source.getNodeType()).contains(field)) {
                    throw new ApiException(
                            "WORKFLOW_VARIABLE_REFERENCE_INVALID",
                            "Workflow variable references an unknown output field: "
                                    + reference.nodeKey() + "." + field,
                            HttpStatus.BAD_REQUEST);
                }
                if (!allowed.contains(source.getId())) {
                    throw new ApiException(
                            "WORKFLOW_VARIABLE_SCOPE_INVALID",
                            "Workflow variables may only reference reachable upstream nodes: "
                                    + reference.template(),
                            HttpStatus.BAD_REQUEST);
                }
            }
        }
    }

    private ConfigVariables readConfigVariables(WorkflowNode node) {
        String configJson = node.getConfigJson() == null || node.getConfigJson().isBlank()
                ? "{}" : node.getConfigJson();
        List<WorkflowVariableResolver.NodeReference> references = new ArrayList<>(
                WorkflowVariableResolver.extractNodeReferences(configJson));
        boolean usesNodeInput = WorkflowVariableResolver.usesContextRoot(configJson, "node_input");
        JsonNode config;
        try {
            config = objectMapper.readTree(configJson);
        } catch (Exception ex) {
            throw new ApiException(
                    "WORKFLOW_NODE_CONFIG_INVALID", "Invalid workflow node config JSON", HttpStatus.BAD_REQUEST);
        }
        for (String field : List.of("querySelector", "outputSelector")) {
            JsonNode selectorNode = config.path(field);
            if (!selectorNode.isTextual() || selectorNode.asText().isBlank()) {
                continue;
            }
            String selector = selectorNode.asText().trim();
            if (selector.startsWith("{{") && selector.endsWith("}}")) {
                usesNodeInput |= WorkflowVariableResolver.usesContextRoot(selector, "node_input");
                continue;
            }
            if (!WorkflowVariableResolver.isValidSelector(selector)) {
                throw new ApiException(
                        "WORKFLOW_VARIABLE_REFERENCE_INVALID",
                        "Workflow selector must be a safe dot-separated path: " + selector,
                        HttpStatus.BAD_REQUEST);
            }
            usesNodeInput |= selector.equals("node_input") || selector.startsWith("node_input.");
            references.addAll(WorkflowVariableResolver.extractNodeReferences("{{#" + selector + "#}}"));
        }
        return new ConfigVariables(List.copyOf(references), usesNodeInput);
    }

    private Map<Long, List<WorkflowNode>> resolveLoopBodiesForReferences(
            List<WorkflowNode> nodes,
            Map<Long, WorkflowNode> nodeById,
            Map<String, WorkflowNode> nodeByKey
    ) {
        Map<Long, List<WorkflowNode>> loopBodies = new LinkedHashMap<>();
        for (WorkflowNode loop : nodes) {
            if (loop.getNodeType() != WorkflowNodeType.LOOP) {
                continue;
            }
            JsonNode config;
            try {
                config = objectMapper.readTree(loop.getConfigJson());
            } catch (Exception ex) {
                throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID", "Invalid loop config", HttpStatus.BAD_REQUEST);
            }
            List<WorkflowNode> body = new ArrayList<>();
            JsonNode keys = config.path("bodyNodeKeys");
            JsonNode ids = config.path("bodyNodeIds");
            if (keys.isArray() && !keys.isEmpty()) {
                for (JsonNode key : keys) {
                    body.add(nodeByKey.get(key.asText()));
                }
            } else if (ids.isArray() && !ids.isEmpty()) {
                for (JsonNode id : ids) {
                    body.add(nodeById.get(id.asLong()));
                }
            }
            loopBodies.put(loop.getId(), List.copyOf(body));
        }
        return loopBodies;
    }

    public List<WorkflowNode> topologicalSort(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<Long, WorkflowNode> allNodesById = new LinkedHashMap<>();
        Map<String, WorkflowNode> allNodesByKey = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            allNodesById.put(node.getId(), node);
            allNodesByKey.put(node.getNodeKey(), node);
        }
        Set<Long> nestedLoopBodyIds = resolveNestedLoopBodyIds(nodes, allNodesById, allNodesByKey);
        List<WorkflowNode> topLevelNodes = nodes.stream()
                .filter(node -> !nestedLoopBodyIds.contains(node.getId()))
                .toList();
        List<WorkflowEdge> topLevelEdges = edges.stream()
                .filter(edge -> !nestedLoopBodyIds.contains(edge.getSourceNode().getId())
                        && !nestedLoopBodyIds.contains(edge.getTargetNode().getId()))
                .toList();

        Map<Long, WorkflowNode> nodeById = new LinkedHashMap<>();
        for (WorkflowNode node : topLevelNodes) {
            nodeById.put(node.getId(), node);
        }

        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowNode node : topLevelNodes) {
            inDegree.put(node.getId(), 0);
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : topLevelEdges) {
            Long sourceId = edge.getSourceNode().getId();
            Long targetId = edge.getTargetNode().getId();
            adjacency.get(sourceId).add(targetId);
            inDegree.merge(targetId, 1, Integer::sum);
        }

        Queue<Long> queue = new ArrayDeque<>();
        for (WorkflowNode node : topLevelNodes) {
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

        if (sorted.size() != topLevelNodes.size()) {
            throw new ApiException("WORKFLOW_HAS_CYCLE", "Workflow contains a cycle", HttpStatus.BAD_REQUEST);
        }
        return sorted;
    }

    public record NodeEvent(String type, Long nodeId, String nodeKey, String nodeType, String nodeName,
                            Map<String, Object> data) {
        public static NodeEvent started(WorkflowNode node) {
            return new NodeEvent("node_started", node.getId(), node.getNodeKey(), node.getNodeType().name(), node.getName(), null);
        }

        public static NodeEvent completed(WorkflowNode node, Map<String, Object> output) {
            return new NodeEvent("node_completed", node.getId(), node.getNodeKey(), node.getNodeType().name(), node.getName(), output);
        }

        public static NodeEvent skipped(WorkflowNode node) {
            return new NodeEvent("node_skipped", node.getId(), node.getNodeKey(), node.getNodeType().name(), node.getName(), null);
        }

        public static NodeEvent failed(WorkflowNode node, String error) {
            return new NodeEvent("node_failed", node.getId(), node.getNodeKey(), node.getNodeType().name(), node.getName(), Map.of("error", error));
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
        Map<String, Object> context = new LinkedHashMap<>(baseCtx.context());
        Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
        context.put("node_outputs", nodeOutputs);
        Map<Long, Map<String, Object>> nodeOutputsById = new HashMap<>();
        Map<Long, String> nodeOutputHandle = new HashMap<>();
        java.util.Set<Long> executed = new java.util.HashSet<>();
        Map<Long, Integer> nodeOrder = new HashMap<>();
        for (int i = 0; i < sortedNodes.size(); i++) {
            nodeOrder.put(sortedNodes.get(i).getId(), i);
        }

        List<List<WorkflowNode>> levels = computeLevels(sortedNodes, edges);

        try (ExecutorService executor = new DelegatingSecurityContextExecutorService(
                Executors.newVirtualThreadPerTaskExecutor())) {
            for (List<WorkflowNode> level : levels) {
                List<CompletableFuture<NodeOutcome>> futures = new ArrayList<>();
                Map<String, Object> levelInput = new LinkedHashMap<>(context);
                levelInput.put("node_outputs", immutableNodeOutputs(nodeOutputs));
                List<NodeTask> sideEffectingNodes = new ArrayList<>();

                for (WorkflowNode node : level) {
                    if (!shouldExecute(node, edges, executed, nodeOutputHandle)) {
                        log.info("Skipping workflow node: id={}, type={}, name={}", node.getId(), node.getNodeType().name(), node.getName());
                        if (eventConsumer != null) {
                            eventConsumer.accept(NodeEvent.skipped(node));
                        }
                        continue;
                    }
                    Map<String, Object> nodeInput = buildNodeContext(
                            node, levelInput, edges, executed, nodeOutputHandle, nodeOutputsById, nodeOrder);
                    if (isSideEffecting(node)) {
                        sideEffectingNodes.add(new NodeTask(node, nodeInput));
                    } else {
                        futures.add(CompletableFuture.supplyAsync(
                                () -> executeNode(node, registry, baseCtx, nodeInput, eventConsumer), executor));
                    }
                }

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                    List<NodeOutcome> outcomes = new ArrayList<>();
                    for (CompletableFuture<NodeOutcome> future : futures) {
                        outcomes.add(future.join());
                    }
                    // TOOL and LOOP can create irreversible external effects. They never overlap and a
                    // failure prevents every later side-effecting node at this level from starting.
                    for (NodeTask task : sideEffectingNodes) {
                        outcomes.add(executeNode(
                                task.node(), registry, baseCtx, task.context(), eventConsumer));
                    }
                    outcomes.sort(Comparator.comparingInt(outcome ->
                            nodeOrder.getOrDefault(outcome.node().getId(), Integer.MAX_VALUE)));
                    for (NodeOutcome outcome : outcomes) {
                        mergeOutcome(outcome, context, nodeOutputs, nodeOutputsById,
                                executed, nodeOutputHandle);
                    }
                } catch (Exception ex) {
                    ApiException apiException = findApiException(ex);
                    if (apiException != null) {
                        throw apiException;
                    }
                    throw new ApiException("WORKFLOW_EXECUTION_FAILED",
                            "Workflow execution failed: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        WorkflowNode end = sortedNodes.stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.END)
                .findFirst().orElse(null);
        if (end == null || !executed.contains(end.getId())) {
            throw new ApiException("WORKFLOW_END_NOT_REACHED",
                    "Workflow completed without executing its END node", HttpStatus.BAD_REQUEST);
        }

        context.put("node_outputs", WorkflowVariableResolver.immutableNodeOutputs(nodeOutputs));
        return context;
    }

    private NodeOutcome executeNode(
            WorkflowNode node,
            NodeExecutorRegistry registry,
            NodeExecutionContext baseCtx,
            Map<String, Object> levelInput,
            Consumer<NodeEvent> eventConsumer
    ) {
        String nodeType = node.getNodeType().name();
        log.info("Executing workflow node: id={}, type={}, name={}", node.getId(), nodeType, node.getName());
        if (eventConsumer != null) {
            eventConsumer.accept(NodeEvent.started(node));
        }
        try {
            WorkflowNodeExecutor nodeExecutor = registry.get(nodeType);
            NodeExecutionContext nodeCtx = new NodeExecutionContext(
                    baseCtx.tenantId(), baseCtx.userId(), baseCtx.agentId(), baseCtx.sessionId(), node,
                    baseCtx.modelConfig(), new LinkedHashMap<>(levelInput), baseCtx.accountingTurn(),
                    baseCtx.mcpTurnSession(), baseCtx.executionBudget(), baseCtx.workflowNodesByKey());
            if (nodeCtx.executionBudget() != null) {
                nodeCtx.executionBudget().checkActive();
            }
            NodeExecutionResult result = WorkflowCheckpointService.execute(node, node.getNodeKey(), () -> nodeExecutor.execute(nodeCtx));
            if (eventConsumer != null) {
                eventConsumer.accept(NodeEvent.completed(node, result.output()));
            }
            return new NodeOutcome(node, result);
        } catch (RuntimeException ex) {
            log.warn("Workflow node failed: id={}, type={}, errorType={}",
                    node.getId(), nodeType, ex.getClass().getSimpleName());
            if (eventConsumer != null) {
                eventConsumer.accept(NodeEvent.failed(node, ex.getMessage()));
            }
            throw ex;
        }
    }

    private Map<String, Object> buildNodeContext(
            WorkflowNode node,
            Map<String, Object> levelInput,
            List<WorkflowEdge> edges,
            java.util.Set<Long> executed,
            Map<Long, String> nodeOutputHandle,
            Map<Long, Map<String, Object>> nodeOutputsById,
            Map<Long, Integer> nodeOrder
    ) {
        Map<String, Object> nodeContext = new LinkedHashMap<>(levelInput);
        List<WorkflowEdge> activeIncoming = edges.stream()
                .filter(edge -> edge.getTargetNode().getId().equals(node.getId()))
                .filter(edge -> isActiveIncomingEdge(edge, executed, nodeOutputHandle))
                .sorted(Comparator
                        .comparingInt((WorkflowEdge edge) -> nodeOrder.getOrDefault(
                                edge.getSourceNode().getId(), Integer.MAX_VALUE))
                        .thenComparing(edge -> stableNodeKey(edge.getSourceNode()))
                        .thenComparing(edge -> edge.getSourceNode().getId()))
                .toList();

        Map<String, Map<String, Object>> directInputs = new LinkedHashMap<>();
        Object primaryInput = null;
        boolean primaryResolved = false;
        int businessInputCount = 0;
        boolean followsStart = false;
        java.util.Set<Long> includedSources = new java.util.HashSet<>();
        for (WorkflowEdge edge : activeIncoming) {
            WorkflowNode source = edge.getSourceNode();
            if (!includedSources.add(source.getId())) {
                continue;
            }
            Map<String, Object> output = nodeOutputsById.getOrDefault(source.getId(), Map.of());
            directInputs.put(stableNodeKey(source), output);
            if (source.getNodeType() == WorkflowNodeType.START) {
                followsStart = true;
            } else {
                businessInputCount++;
                if (!primaryResolved) {
                    primaryInput = WorkflowVariableResolver.primaryOutput(output);
                    primaryResolved = true;
                }
            }
        }
        if (businessInputCount > 1) {
            if (readConfigVariables(node).usesNodeInput()) {
                throw new ApiException(
                        "AMBIGUOUS_NODE_INPUT",
                        "node_input is ambiguous with multiple active predecessors; use nodeKey.field instead",
                        HttpStatus.BAD_REQUEST);
            }
            primaryInput = null;
            primaryResolved = true;
        }
        if (!primaryResolved && (followsStart || node.getNodeType() == WorkflowNodeType.START)) {
            primaryInput = levelInput.get("user_input");
        }

        nodeContext.put("node_inputs", java.util.Collections.unmodifiableMap(directInputs));
        nodeContext.put("node_input", primaryInput);
        return nodeContext;
    }

    private String stableNodeKey(WorkflowNode node) {
        return node.getNodeKey() == null ? "node_" + node.getId() : node.getNodeKey();
    }

    private Map<String, Map<String, Object>> immutableNodeOutputs(
            Map<String, Map<String, Object>> nodeOutputs
    ) {
        return WorkflowVariableResolver.immutableNodeOutputs(nodeOutputs);
    }

    private void mergeOutcome(
            NodeOutcome outcome,
            Map<String, Object> context,
            Map<String, Map<String, Object>> nodeOutputs,
            Map<Long, Map<String, Object>> nodeOutputsById,
            java.util.Set<Long> executed,
            Map<Long, String> nodeOutputHandle
    ) {
        WorkflowNode node = outcome.node();
        NodeExecutionResult result = outcome.result();
        Map<String, Object> output = result.output() == null
                ? Map.of() : WorkflowVariableResolver.immutableOutput(result.output());
        for (Map.Entry<String, Map<String, Object>> child : result.childNodeOutputs().entrySet()) {
            nodeOutputs.put(child.getKey(), WorkflowVariableResolver.immutableOutput(child.getValue()));
        }
        String outputKey = stableNodeKey(node);
        nodeOutputs.put(outputKey, output);
        nodeOutputsById.put(node.getId(), output);
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            // node_outputs is authoritative. This flat projection remains only for old workflows;
            // merge order follows the stable topological node order, never completion timing.
            context.put(entry.getKey(), entry.getValue());
        }
        executed.add(node.getId());
        if (result.nextHandle() != null) {
            nodeOutputHandle.put(node.getId(), result.nextHandle());
        }
        log.info("Completed workflow node: id={}, type={}", node.getId(), node.getNodeType().name());
    }

    private boolean isSideEffecting(WorkflowNode node) {
        return node.getNodeType() == WorkflowNodeType.LLM
                || node.getNodeType() == WorkflowNodeType.KNOWLEDGE
                || node.getNodeType() == WorkflowNodeType.TOOL
                || node.getNodeType() == WorkflowNodeType.SANDBOX
                || node.getNodeType() == WorkflowNodeType.LOOP;
    }

    private ApiException findApiException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApiException apiException) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
    }

    private record NodeOutcome(WorkflowNode node, NodeExecutionResult result) {
    }

    private record NodeTask(WorkflowNode node, Map<String, Object> context) {
    }

    private record ConfigVariables(
            List<WorkflowVariableResolver.NodeReference> references,
            boolean usesNodeInput
    ) {
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
            if (isActiveIncomingEdge(edge, executed, nodeOutputHandle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveIncomingEdge(
            WorkflowEdge edge,
            java.util.Set<Long> executed,
            Map<Long, String> nodeOutputHandle
    ) {
        Long sourceId = edge.getSourceNode().getId();
        if (!executed.contains(sourceId)) {
            return false;
        }
        String sourceHandle = nodeOutputHandle.get(sourceId);
        String edgeSourceHandle = edge.getSourceHandle();
        return sourceHandle == null || sourceHandle.equals(edgeSourceHandle);
    }
}
