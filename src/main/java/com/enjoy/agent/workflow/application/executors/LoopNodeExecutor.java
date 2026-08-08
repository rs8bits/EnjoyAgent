package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.NodeExecutorRegistry;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoopNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LoopNodeExecutor.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final WorkflowNodeRepository nodeRepository;
    private final NodeExecutorRegistry registry;
    private final ObjectMapper objectMapper;

    public LoopNodeExecutor(WorkflowNodeRepository nodeRepository, @Lazy NodeExecutorRegistry registry, ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "LOOP";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        LoopConfig config = parseConfig(ctx.node().getConfigJson());
        List<WorkflowNode> bodyNodes = loadBodyNodes(config.bodyNodeIds);

        int iterations = 0;
        String breakReason = "max_iterations";
        for (int i = 0; i < config.maxIterations; i++) {
            iterations = i + 1;
            log.info("Loop iteration {}/{}", iterations, config.maxIterations);

            for (WorkflowNode bodyNode : bodyNodes) {
                WorkflowNodeExecutor executor = registry.get(bodyNode.getNodeType().name());
                NodeExecutionContext bodyCtx = ctx.withNode(bodyNode);
                NodeExecutionResult result = executor.execute(bodyCtx);
                if (result.output() != null) {
                    ctx.context().putAll(result.output());
                }
            }

            if (config.exitCondition != null && !config.exitCondition.isBlank()) {
                String resolved = resolveTemplates(config.exitCondition, ctx.context());
                boolean shouldExit = evaluateCondition(resolved);
                if (shouldExit) {
                    breakReason = "exit_condition_met";
                    log.info("Loop exit condition met: {}", config.exitCondition);
                    break;
                }
            }
        }

        ctx.context().put("loop_iterations", iterations);
        ctx.context().put("loop_break_reason", breakReason);
        return new NodeExecutionResult(null, Map.of(
                "loop_iterations", iterations,
                "loop_break_reason", breakReason
        ));
    }

    private List<WorkflowNode> loadBodyNodes(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ApiException("LOOP_EMPTY_BODY", "Loop node must have at least one body node", HttpStatus.BAD_REQUEST);
        }
        List<WorkflowNode> nodes = nodeRepository.findAllById(ids);
        if (nodes.size() != ids.size()) {
            throw new ApiException("LOOP_BODY_NOT_FOUND", "Some loop body nodes not found", HttpStatus.BAD_REQUEST);
        }
        nodes.sort((a, b) -> ids.indexOf(a.getId()) - ids.indexOf(b.getId()));
        return nodes;
    }

    private String resolveTemplates(String expression, Map<String, Object> context) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(expression);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean evaluateCondition(String expression) {
        String trimmed = expression.trim();
        if ("false".equalsIgnoreCase(trimmed) || trimmed.isEmpty()) return false;
        if ("true".equalsIgnoreCase(trimmed)) return true;
        return !trimmed.isEmpty();
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
            int maxIterations,
            String exitCondition
    ) {
    }
}
