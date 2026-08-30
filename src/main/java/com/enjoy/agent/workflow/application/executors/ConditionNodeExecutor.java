package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.enjoy.agent.workflow.application.WorkflowConditionEvaluator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ConditionNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeExecutor.class);
    private final ObjectMapper objectMapper;
    private final WorkflowConditionEvaluator conditionEvaluator;

    public ConditionNodeExecutor(ObjectMapper objectMapper, WorkflowConditionEvaluator conditionEvaluator) {
        this.objectMapper = objectMapper;
        this.conditionEvaluator = conditionEvaluator;
    }

    @Override
    public String nodeType() {
        return "CONDITION";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        ConditionConfig config = parseConfig(ctx.node().getConfigJson());
        String expression = conditionEvaluator.resolveTemplates(config.expression, ctx.context());
        String expectedValue = conditionEvaluator.resolveTemplates(config.value, ctx.context());
        boolean result = conditionEvaluator.evaluate(expression, config.operator, expectedValue);

        log.debug("Workflow condition evaluated: nodeKey={}, result={}", ctx.node().getNodeKey(), result);
        return new NodeExecutionResult(result ? "true" : "false", Map.of(
                "condition_result", result
        ));
    }

    private ConditionConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ConditionConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid condition node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private record ConditionConfig(
            String expression,
            String operator,
            String value
    ) {
    }
}
