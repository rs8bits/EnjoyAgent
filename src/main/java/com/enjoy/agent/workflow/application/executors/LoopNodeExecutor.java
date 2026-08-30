package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.NodeExecutorRegistry;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.enjoy.agent.workflow.application.WorkflowConditionEvaluator;
import com.enjoy.agent.workflow.application.WorkflowVariableResolver;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoopNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LoopNodeExecutor.class);
    private static final int MAX_ITERATIONS = 100;
    private static final int MAX_BODY_NODES = 50;

    private final WorkflowNodeRepository nodeRepository;
    private final NodeExecutorRegistry registry;
    private final ObjectMapper objectMapper;
    private final WorkflowConditionEvaluator conditionEvaluator;

    public LoopNodeExecutor(
            WorkflowNodeRepository nodeRepository,
            @Lazy NodeExecutorRegistry registry,
            ObjectMapper objectMapper,
            WorkflowConditionEvaluator conditionEvaluator
    ) {
        this.nodeRepository = nodeRepository;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.conditionEvaluator = conditionEvaluator;
    }

    @Override
    public String nodeType() {
        return "LOOP";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        LoopConfig config = parseConfig(ctx.node().getConfigJson());
        int maxIterations = normalizeMaxIterations(config.maxIterations);
        List<WorkflowNode> bodyNodes = loadBodyNodes(ctx, config);

        int iterations = 0;
        String breakReason = "max_iterations";
        Map<String, Object> loopOutput = new LinkedHashMap<>();
        Map<String, Map<String, Object>> namespace = copyNodeOutputs(ctx.context().get("node_outputs"));
        Map<String, Map<String, Object>> bodyOutputs = new LinkedHashMap<>();
        Map<String, Object> previousOutput = null;
        String previousNodeKey = null;
        for (int i = 0; i < maxIterations; i++) {
            if (ctx.executionBudget() != null) {
                ctx.executionBudget().checkActive();
            }
            iterations = i + 1;
            log.debug("Workflow loop iteration {}/{} for nodeKey={}",
                    iterations, maxIterations, ctx.node().getNodeKey());

            for (WorkflowNode bodyNode : bodyNodes) {
                WorkflowNodeExecutor executor = registry.get(bodyNode.getNodeType().name());
                Map<String, Object> bodyContext = new LinkedHashMap<>(ctx.context());
                bodyContext.put("node_outputs", WorkflowVariableResolver.immutableNodeOutputs(namespace));
                if (previousOutput != null) {
                    bodyContext.put("node_inputs", Map.of(previousNodeKey, previousOutput));
                    bodyContext.put("node_input", WorkflowVariableResolver.primaryOutput(previousOutput));
                }
                NodeExecutionContext bodyCtx = ctx.withNodeAndContext(bodyNode, bodyContext);
                if (bodyCtx.executionBudget() != null) {
                    bodyCtx.executionBudget().checkActive();
                }
                NodeExecutionResult result = executor.execute(bodyCtx);
                Map<String, Object> output = WorkflowVariableResolver.immutableOutput(result.output());
                String bodyNodeKey = bodyNode.getNodeKey();
                bodyOutputs.put(bodyNodeKey, output);
                namespace.put(bodyNodeKey, output);
                for (Map.Entry<String, Map<String, Object>> child : result.childNodeOutputs().entrySet()) {
                    Map<String, Object> childOutput = WorkflowVariableResolver.immutableOutput(child.getValue());
                    bodyOutputs.put(child.getKey(), childOutput);
                    namespace.put(child.getKey(), childOutput);
                }
                ctx.context().put("node_outputs", WorkflowVariableResolver.immutableNodeOutputs(namespace));
                if (!output.isEmpty()) {
                    ctx.context().putAll(output);
                    loopOutput.putAll(output);
                }
                previousOutput = output;
                previousNodeKey = bodyNodeKey;
            }

            if (config.exitCondition != null && !config.exitCondition.isBlank()) {
                String resolved = conditionEvaluator.resolveTemplates(config.exitCondition, ctx.context());
                String expected = conditionEvaluator.resolveTemplates(config.exitValue, ctx.context());
                boolean shouldExit = config.exitOperator == null || config.exitOperator.isBlank()
                        ? conditionEvaluator.evaluateInline(resolved)
                        : conditionEvaluator.evaluate(resolved, config.exitOperator, expected);
                if (shouldExit) {
                    breakReason = "exit_condition_met";
                    log.debug("Workflow loop exit condition met for nodeKey={}", ctx.node().getNodeKey());
                    break;
                }
            }
        }

        ctx.context().put("loop_iterations", iterations);
        ctx.context().put("loop_break_reason", breakReason);
        loopOutput.put("loop_iterations", iterations);
        loopOutput.put("loop_break_reason", breakReason);
        loopOutput.put("body_outputs", WorkflowVariableResolver.immutableNodeOutputs(bodyOutputs));
        return new NodeExecutionResult(
                null,
                WorkflowVariableResolver.immutableOutput(loopOutput),
                WorkflowVariableResolver.immutableNodeOutputs(bodyOutputs));
    }

    private Map<String, Map<String, Object>> copyNodeOutputs(Object value) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> namespace)) {
            return copy;
        }
        for (Map.Entry<?, ?> entry : namespace.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Map<?, ?> rawOutput) {
                Map<String, Object> output = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : rawOutput.entrySet()) {
                    if (field.getKey() instanceof String fieldName) {
                        output.put(fieldName, field.getValue());
                    }
                }
                copy.put(key, WorkflowVariableResolver.immutableOutput(output));
            }
        }
        return copy;
    }

    private List<WorkflowNode> loadBodyNodes(NodeExecutionContext ctx, LoopConfig config) {
        Long workflowId = ctx.node().getWorkflow() == null ? null : ctx.node().getWorkflow().getId();
        if (workflowId == null || ctx.tenantId() == null) {
            throw new ApiException("LOOP_WORKFLOW_SCOPE_INVALID",
                    "Loop node does not belong to the executing tenant/workflow", HttpStatus.BAD_REQUEST);
        }

        List<WorkflowNode> nodes;
        List<?> orderedReferences;
        if (config.bodyNodeKeys != null && !config.bodyNodeKeys.isEmpty()) {
            requireBodySize(config.bodyNodeKeys.size());
            nodes = resolveByKeysFromExecutionGraph(ctx, config.bodyNodeKeys);
            if (nodes == null) {
                nodes = nodeRepository.findAllByWorkflow_IdAndNodeKeyIn(workflowId, config.bodyNodeKeys);
            }
            orderedReferences = config.bodyNodeKeys;
        } else if (config.bodyNodeIds != null && !config.bodyNodeIds.isEmpty()) {
            // Legacy ids remain accepted, but are now constrained to this workflow.
            requireBodySize(config.bodyNodeIds.size());
            nodes = resolveByIdsFromExecutionGraph(ctx, config.bodyNodeIds);
            if (nodes == null) {
                nodes = nodeRepository.findAllByWorkflow_IdAndIdIn(workflowId, config.bodyNodeIds);
            }
            orderedReferences = config.bodyNodeIds;
        } else {
            throw new ApiException("LOOP_EMPTY_BODY", "Loop node must have at least one body node", HttpStatus.BAD_REQUEST);
        }

        long distinctReferenceCount = orderedReferences.stream().distinct().count();
        if (distinctReferenceCount != orderedReferences.size()) {
            throw new ApiException("LOOP_BODY_DUPLICATED",
                    "Loop body references must be unique", HttpStatus.BAD_REQUEST);
        }
        if (nodes.size() != distinctReferenceCount) {
            throw new ApiException("LOOP_BODY_NOT_FOUND", "Some loop body nodes not found", HttpStatus.BAD_REQUEST);
        }
        Map<Object, WorkflowNode> byReference = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            Object reference = config.bodyNodeKeys != null && !config.bodyNodeKeys.isEmpty()
                    ? node.getNodeKey() : node.getId();
            byReference.put(reference, node);
        }
        List<WorkflowNode> ordered = orderedReferences.stream().distinct().map(byReference::get).toList();
        for (WorkflowNode node : ordered) {
            if (node == null || Objects.equals(node.getId(), ctx.node().getId())
                    || node.getNodeType().name().equals("START")
                    || node.getNodeType().name().equals("END")
                    || node.getNodeType().name().equals("LOOP")) {
                throw new ApiException("LOOP_BODY_INVALID",
                        "Loop body cannot contain START, END, LOOP, or the loop node itself", HttpStatus.BAD_REQUEST);
            }
        }
        return ordered;
    }

    private List<WorkflowNode> resolveByKeysFromExecutionGraph(
            NodeExecutionContext ctx,
            List<String> keys
    ) {
        if (ctx.workflowNodesByKey().isEmpty()) {
            return null;
        }
        return keys.stream().map(ctx.workflowNodesByKey()::get).filter(Objects::nonNull).toList();
    }

    private List<WorkflowNode> resolveByIdsFromExecutionGraph(
            NodeExecutionContext ctx,
            List<Long> ids
    ) {
        if (ctx.workflowNodesByKey().isEmpty()) {
            return null;
        }
        Map<Long, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : ctx.workflowNodesByKey().values()) {
            byId.put(node.getId(), node);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private int normalizeMaxIterations(Integer configured) {
        int value = configured == null ? 10 : configured;
        if (value < 1 || value > MAX_ITERATIONS) {
            throw new ApiException("LOOP_ITERATION_LIMIT_INVALID",
                    "Loop maxIterations must be between 1 and " + MAX_ITERATIONS, HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private void requireBodySize(int size) {
        if (size > MAX_BODY_NODES) {
            throw new ApiException("LOOP_BODY_LIMIT_EXCEEDED",
                    "Loop body cannot contain more than " + MAX_BODY_NODES + " nodes", HttpStatus.BAD_REQUEST);
        }
    }

    private LoopConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, LoopConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID", "Invalid loop config", HttpStatus.BAD_REQUEST);
        }
    }

    private record LoopConfig(
            List<Long> bodyNodeIds,
            List<String> bodyNodeKeys,
            Integer maxIterations,
            String exitCondition,
            String exitOperator,
            String exitValue
    ) {
    }
}
